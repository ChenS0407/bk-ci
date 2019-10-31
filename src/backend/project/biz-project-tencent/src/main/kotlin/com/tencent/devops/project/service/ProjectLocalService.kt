package com.tencent.devops.project.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.auth.api.BSAuthProjectApi
import com.tencent.devops.common.auth.api.BkAuthProperties
import com.tencent.devops.common.auth.api.pojo.BkAuthGroup
import com.tencent.devops.common.auth.code.AuthServiceCode
import com.tencent.devops.common.auth.code.BSPipelineAuthServiceCode
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.gray.Gray
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.common.web.mq.*
import com.tencent.devops.model.project.tables.records.TProjectRecord
import com.tencent.devops.project.constant.ProjectMessageCode
import com.tencent.devops.project.dao.ProjectDao
import com.tencent.devops.project.jmx.api.ProjectJmxApi
import com.tencent.devops.project.pojo.*
import com.tencent.devops.project.pojo.enums.ProjectChannelCode
import com.tencent.devops.project.pojo.enums.ProjectTypeEnum
import com.tencent.devops.project.pojo.enums.ProjectValidateType
import com.tencent.devops.project.pojo.tof.Response
import com.tencent.devops.project.service.job.SynProjectService.Companion.ENGLISH_NAME_PATTERN
import com.tencent.devops.project.service.s3.S3Service
import com.tencent.devops.project.service.tof.TOFService
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.glassfish.jersey.media.multipart.FormDataContentDisposition
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Color.gray
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.*
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.collections.HashMap

@Service
class ProjectLocalService @Autowired constructor(
        private val dslContext: DSLContext,
        private val projectDao: ProjectDao,
        private val rabbitTemplate: RabbitTemplate,
        private val s3Service: S3Service,
        private val objectMapper: ObjectMapper,
        private val tofService: TOFService,
        private val redisOperation: RedisOperation,
        private val bkAuthProjectApi: BSAuthProjectApi,
        private val bkAuthProperties: BkAuthProperties,
        private val bsPipelineAuthServiceCode: BSPipelineAuthServiceCode,
        private val gray: Gray,
        private val jmxApi: ProjectJmxApi
) {

    private var authUrl: String = "${bkAuthProperties.url}/projects"

    @Value("\${paas_cc.url}")
    private lateinit var ccUrl: String

    fun create(userId: String, accessToken: String, projectCreateInfo: ProjectCreateInfo): String {
        validate(ProjectValidateType.project_name, projectCreateInfo.projectName)
        validate(ProjectValidateType.english_name, projectCreateInfo.englishName)

        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            // �������ͼƬ
            val logoFile = drawImage(projectCreateInfo.englishName.substring(0, 1).toUpperCase())
            try {
                // ���ͷ�����
                val logoAddress = s3Service.saveLogo(logoFile, projectCreateInfo.englishName)

                // ����AUTH��Ŀ
                val authUrl = "$authUrl?access_token=$accessToken"
                val param: MutableMap<String, String> = mutableMapOf("project_code" to projectCreateInfo.englishName)
                val mediaType = MediaType.parse("application/json; charset=utf-8")
                val json = objectMapper.writeValueAsString(param)
                val requestBody = RequestBody.create(mediaType, json)
                val request = Request.Builder().url(authUrl).post(requestBody).build()
                val responseContent = request(request, MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.CALL_PEM_FAIL))
                val result = objectMapper.readValue<Result<AuthProjectForCreateResult>>(responseContent)
                if (result.isNotOk()) {
                    logger.warn("Fail to create the project of response $responseContent")
                    throw OperationException(MessageCodeUtil.generateResponseDataObject<String>(
                            ProjectMessageCode.CALL_PEM_FAIL_PARM, arrayOf(result.message!!)).message!!)
                }
                val authProjectForCreateResult = result.data
                val projectId = if (authProjectForCreateResult != null) {
                    if (authProjectForCreateResult.project_id.isBlank()) {
                        throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_CREATE_FAIL))
                    }
                    authProjectForCreateResult.project_id
                } else {
                    logger.warn("Fail to get the project id from response $responseContent")
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_CREATE_ID_INVALID))
                }
                val userDeptDetail = tofService.getUserDeptDetail(userId, "") // ��ȡ�û�������Ϣ
                try {
                    projectDao.create(
                            dslContext = dslContext,
                            userId = userId,
                            logoAddress = logoAddress,
                            projectCreateInfo = projectCreateInfo,
                            userDeptDetail = userDeptDetail,
                            projectId = projectId,
                            channelCode = ProjectChannelCode.BS
                    )
                } catch (e: DuplicateKeyException) {
                    logger.warn("Duplicate project $projectCreateInfo", e)
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NAME_EXIST))
                } catch (t: Throwable) {
                    logger.warn("Fail to create the project ($projectCreateInfo)", t)
                    deleteProjectFromAuth(projectId, accessToken)
                    throw t
                }

                rabbitTemplate.convertAndSend(
                        EXCHANGE_PAASCC_PROJECT_CREATE,
                        ROUTE_PAASCC_PROJECT_CREATE, PaasCCCreateProject(
                        userId = userId,
                        accessToken = accessToken,
                        projectId = projectId,
                        retryCount = 0,
                        projectCreateInfo = projectCreateInfo
                )
                )
                success = true
                return projectId
            } finally {
                if (logoFile.exists()) {
                    logoFile.delete()
                }
            }
        } finally {
//            jmxApi.execute(PROJECT_CREATE, System.currentTimeMillis() - startEpoch, success)
        }
    }

    fun getProjectEnNamesByOrganization(userId: String, bgId: Long?, deptName: String?, centerName: String?, interfaceName: String?): List<String> {
        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            val list = projectDao.listByOrganization(
                    dslContext = dslContext,
                    bgId = bgId,
                    deptName = deptName,
                    centerName = centerName
            )?.filter { it.enabled == null || it.enabled }?.map { it.englishName }?.toList() ?: emptyList()
            success = true
            return list
        } finally {
            jmxApi.execute("getProjectEnNamesByOrganization", System.currentTimeMillis() - startEpoch, success)
            logger.info("It took ${System.currentTimeMillis() - startEpoch}ms to list project EnNames,userName:$userId")
        }
    }

    fun getOrCreatePreProject(userId: String, accessToken: String): ProjectVO {
        val projectCode = "_$userId"
        var userProjectRecord = projectDao.getByEnglishName(dslContext, projectCode)
        if (userProjectRecord != null) {
            return packagingBean(userProjectRecord, setOf())
        }

        val projectCreateInfo = ProjectCreateInfo(
                projectName = projectCode,
                englishName = projectCode,
                projectType = ProjectTypeEnum.SUPPORT_PRODUCT.index,
                description = "prebuild project for $userId",
                bgId = 0L,
                bgName = "",
                deptId = 0L,
                deptName = "",
                centerId = 0L,
                centerName = "",
                secrecy = false,
                kind = 0
        )

        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            // �������ͼƬ
            val logoFile = drawImage(projectCreateInfo.englishName.substring(0, 1).toUpperCase())
            try {
                // ���ͷ�����
                val logoAddress = s3Service.saveLogo(logoFile, projectCreateInfo.englishName)

                var projectId = getProjectIdInAuth(projectCode, accessToken)

                if (null == projectId) {
                    // ����AUTH��Ŀ
                    val authUrl = "$authUrl?access_token=$accessToken"
                    val param: MutableMap<String, String> =
                            mutableMapOf("project_code" to projectCreateInfo.englishName)
                    val mediaType = MediaType.parse("application/json; charset=utf-8")
                    val json = objectMapper.writeValueAsString(param)
                    val requestBody = RequestBody.create(mediaType, json)
                    val request = Request.Builder().url(authUrl).post(requestBody).build()
                    val responseContent = request(request, MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.CALL_PEM_FAIL))
                    val result = objectMapper.readValue<Result<AuthProjectForCreateResult>>(responseContent)
                    if (result.isNotOk()) {
                        logger.warn("Fail to create the project of response $responseContent")
                        throw OperationException(MessageCodeUtil.generateResponseDataObject<String>(
                                ProjectMessageCode.CALL_PEM_FAIL_PARM, arrayOf(result.message!!)).message!!)
                    }
                    val authProjectForCreateResult = result.data
                    projectId = if (authProjectForCreateResult != null) {
                        if (authProjectForCreateResult.project_id.isBlank()) {
                            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_CREATE_ID_INVALID))
                        }
                        authProjectForCreateResult.project_id
                    } else {
                        logger.warn("Fail to get the project id from response $responseContent")
                        throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_CREATE_ID_INVALID))
                    }
                }
                val userDeptDetail = tofService.getUserDeptDetail(userId, "") // ��ȡ�û�������Ϣ
                try {
                    projectDao.create(
                            dslContext = dslContext,
                            userId = userId,
                            logoAddress = logoAddress,
                            projectCreateInfo = projectCreateInfo,
                            userDeptDetail = userDeptDetail,
                            projectId = projectId,
                            channelCode = ProjectChannelCode.BS
                    )
                } catch (e: DuplicateKeyException) {
                    logger.warn("Duplicate project $projectCreateInfo", e)
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NAME_EXIST))
                } catch (t: Throwable) {
                    logger.warn("Fail to create the project ($projectCreateInfo)", t)
                    deleteProjectFromAuth(projectId, accessToken)
                    throw t
                }

                rabbitTemplate.convertAndSend(
                        EXCHANGE_PAASCC_PROJECT_CREATE,
                        ROUTE_PAASCC_PROJECT_CREATE, PaasCCCreateProject(
                        userId = userId,
                        accessToken = accessToken,
                        projectId = projectId,
                        retryCount = 0,
                        projectCreateInfo = projectCreateInfo
                )
                )
                success = true
            } finally {
                if (logoFile.exists()) {
                    logoFile.delete()
                }
            }
        } finally {
            jmxApi.execute(PROJECT_CREATE, System.currentTimeMillis() - startEpoch, success)
        }

        userProjectRecord = projectDao.getByEnglishName(dslContext, projectCode)
        return packagingBean(userProjectRecord!!, setOf())
    }

    fun getProjectByGroup(userId: String, bgName: String?, deptName: String?, centerName: String?): List<ProjectVO> {
        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            val grayProjectSet = grayProjectSet()
            val list = ArrayList<ProjectVO>()
            projectDao.listByGroup(dslContext, bgName, deptName, centerName).filter { it.enabled == null || it.enabled }
                    .map {
                        list.add(packagingBean(it, grayProjectSet))
                    }
            success = true
            return list
        } finally {
            jmxApi.execute(PROJECT_LIST, System.currentTimeMillis() - startEpoch, success)
            logger.info("It took ${System.currentTimeMillis() - startEpoch}ms to list projects,userName:$userId")
        }
    }

    fun updateUsableStatus(userId: String, projectId: String, enabled: Boolean) {
        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            logger.info("[$userId|$projectId|$enabled] Start to update project usable status")
            if (bkAuthProjectApi.getProjectUsers(bsPipelineAuthServiceCode, projectId, BkAuthGroup.MANAGER).contains(
                            userId
                    )
            ) {
                val updateCnt = projectDao.updateUsableStatus(dslContext, userId, projectId, enabled)
                if (updateCnt != 1) {
                    logger.warn("�������ݿ�����������Ϊ:$updateCnt")
                }
            } else {
                throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_CHECK_FAIL))
            }
            logger.info("[$userId|[$projectId] Project usable status is changed to $enabled")
            success = true
        } finally {
            jmxApi.execute(PROJECT_UPDATE, System.currentTimeMillis() - startEpoch, success)
        }
    }

    fun getByEnglishName(accessToken: String, englishName: String): ProjectVO {
        val projectVO = getByEnglishName(englishName)
        val projectAuthIds = getAuthProjectIds(accessToken)
        if (!projectAuthIds.contains(projectVO!!.projectId)) {
            logger.warn("The user don't have the permission to get the project $englishName")
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NOT_EXIST))
        }
        return projectVO
    }

    fun getByEnglishName(englishName: String): ProjectVO? {
        val record = projectDao.getByEnglishName(dslContext, englishName) ?: return null
        return packagingBean(record, grayProjectSet())
    }

    fun getProjectUsers(accessToken: String, userId: String, projectCode: String): Result<List<String>?> {
        logger.info("getProjectUsers accessToken is :$accessToken,userId is :$userId,projectCode is :$projectCode")
        // ����û��Ƿ��в�ѯ��Ŀ���û��б��Ȩ��
        val validateResult = verifyUserProjectPermission(accessToken, projectCode, userId)
        logger.info("getProjectUsers validateResult is :$validateResult")
        val validateFlag = validateResult.data
        if (null == validateFlag || !validateFlag) {
            val messageResult = MessageCodeUtil.generateResponseDataObject<String>(CommonMessageCode.PERMISSION_DENIED)
            return Result(messageResult.status, messageResult.message, null)
        }
        val projectUserList = bkAuthProjectApi.getProjectUsers(bsPipelineAuthServiceCode, projectCode)
        logger.info("getProjectUsers projectUserList is :$projectUserList")
        return Result(projectUserList)
    }

    fun getProjectUserRoles(accessToken: String, userId: String, projectCode: String, serviceCode: AuthServiceCode): List<UserRole> {
        val groupAndUsersList = bkAuthProjectApi.getProjectGroupAndUserList(serviceCode, projectCode)
        return groupAndUsersList.filter { it.userIdList.contains(userId) }
                .map { UserRole(it.displayName, it.roleId, it.roleName, it.type) }
    }


     fun update(userId: String, accessToken: String, projectId: String, projectUpdateInfo: ProjectUpdateInfo) {
        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            try {
                val appName = if (projectUpdateInfo.ccAppId != null && projectUpdateInfo.ccAppId!! > 0) {
                    tofService.getCCAppName(projectUpdateInfo.ccAppId!!)
                } else {
                    null
                }
                projectUpdateInfo.ccAppName = appName
                projectDao.update(dslContext, userId, projectId, projectUpdateInfo)
            } catch (e: DuplicateKeyException) {
                logger.warn("Duplicate project $projectUpdateInfo", e)
                throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NAME_EXIST))
            }
            rabbitTemplate.convertAndSend(
                    EXCHANGE_PAASCC_PROJECT_UPDATE,
                    ROUTE_PAASCC_PROJECT_UPDATE, PaasCCUpdateProject(
                    userId = userId,
                    accessToken = accessToken,
                    projectId = projectId,
                    retryCount = 0,
                    projectUpdateInfo = projectUpdateInfo
            )
            )
            success = true
        } finally {
            jmxApi.execute(PROJECT_UPDATE, System.currentTimeMillis() - startEpoch, success)
        }
    }

    fun updateLogo(userId: String, accessToken: String, projectId: String, inputStream: InputStream, disposition: FormDataContentDisposition): Result<Boolean> {
        logger.info("Update the logo of project $projectId")
        val project = projectDao.get(dslContext, projectId)
        if (project != null) {
            var logoFile: File? = null
            try {
                logoFile = convertFile(inputStream)
                val logoAddress = s3Service.saveLogo(logoFile, project.englishName)
                projectDao.updateLogoAddress(dslContext, userId, projectId, logoAddress)
                rabbitTemplate.convertAndSend(
                        EXCHANGE_PAASCC_PROJECT_UPDATE_LOGO,
                        ROUTE_PAASCC_PROJECT_UPDATE_LOGO, PaasCCUpdateProjectLogo(
                        userId = userId,
                        accessToken = accessToken,
                        projectId = projectId,
                        retryCount = 0,
                        projectUpdateLogoInfo = ProjectUpdateLogoInfo(logoAddress, userId)
                )
                )
            } catch (e: Exception) {
                logger.warn("fail update projectLogo", e)
                throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.UPDATE_LOGO_FAIL))
            } finally {
                logoFile?.delete()
            }
        } else {
            logger.warn("$project is null or $project is empty")
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.QUERY_PROJECT_FAIL))
        }
        return Result(true)
    }

    fun list(accessToken: String, includeDisable: Boolean?): List<ProjectVO> {
        val startEpoch = System.currentTimeMillis()
        var success = false
        try {
            val projectIdList = getAuthProjectIds(accessToken).toSet()
            val list = ArrayList<ProjectVO>()

            val grayProjectSet = grayProjectSet()

            projectDao.list(dslContext, projectIdList).filter {
                includeDisable == true || it.enabled == null || it.enabled
            }.map {
                list.add(packagingBean(it, grayProjectSet))
            }
            success = true
            return list
        } finally {
            jmxApi.execute(PROJECT_LIST, System.currentTimeMillis() - startEpoch, success)
            logger.info("It took ${System.currentTimeMillis() - startEpoch}ms to list projects")
        }
    }

    private fun drawImage(logoStr: String): File {
        val logoBackgroundColor = arrayOf("#FF5656", "#FFB400", "#30D878", "#3C96FF")
        val max = logoBackgroundColor.size - 1
        val min = 0
        val random = Random()
        val backgroundIndex = random.nextInt(max) % (max - min + 1) + min
        val width = 128
        val height = 128
        // ����BufferedImage����
        val bi = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        // ��ȡGraphics2D
        val g2d = bi.createGraphics()
        // ����͸����
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 1.0f)

        when (backgroundIndex) {
            0 -> {
                g2d.background = Color.RED
            }
            1 -> {
                g2d.background = Color.YELLOW
            }
            2 -> {
                g2d.background = Color.GREEN
            }
            3 -> {
                g2d.background = Color.BLUE
            }
        }
        g2d.clearRect(0, 0, width, height)
        g2d.color = Color.WHITE
        g2d.stroke = BasicStroke(1.0f)
        val font = Font("����", Font.PLAIN, 64)
        g2d.font = font
        val fontMetrics = g2d.fontMetrics
        val heightAscent = fontMetrics.ascent

        val context = g2d.fontRenderContext
        val stringBounds = font.getStringBounds(logoStr, context)
        val fontWidth = stringBounds.width.toFloat()

        g2d.drawString(
                logoStr,
                (width / 2 - fontWidth / 2),
                (height / 2 + heightAscent / 2).toFloat()
        )
        // ͸�������� ����
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
        // �ͷŶ���
        g2d.dispose()
        // �����ļ�
        val logo = Files.createTempFile("default_", ".png").toFile()
        ImageIO.write(bi, "png", logo)
        return logo
    }

    fun verifyUserProjectPermission(accessToken: String, projectCode: String, userId: String): Result<Boolean> {
        val url = "$authUrl/$projectCode/users/$userId/verfiy?access_token=$accessToken"
        logger.info("the verifyUserProjectPermission url is:$url")
        val body = RequestBody.create(MediaType.parse(MessageProperties.CONTENT_TYPE_JSON), "{}")
        val request = Request.Builder().url(url).post(body).build()
        val responseContent = request(request, "verifyUserProjectPermission error")
        val result = objectMapper.readValue<Result<Any?>>(responseContent)
        logger.info("the verifyUserProjectPermission result is:$result")
        if (result.isOk()) {
            return Result(true)
        }
        return Result(false)
    }

    private fun getAuthProjectIds(accessToken: String): List<String/*projectId*/> {
        val url = "$authUrl?access_token=$accessToken"
        val request = Request.Builder().url(url).get().build()
        val responseContent = request(request, "��Ȩ�����Ļ�ȡ�û�����Ŀ��Ϣʧ��")
        val result = objectMapper.readValue<Result<ArrayList<AuthProjectForList>>>(responseContent)
        if (result.isNotOk()) {
            logger.warn("Fail to get the project info with response $responseContent")
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_QUERY_ERROR))
        }
        if (result.data == null) {
            return emptyList()
        }

        return result.data!!.map {
            it.project_id
        }.toList()
    }

    private fun grayProjectSet() =
            (redisOperation.getSetMembers(gray.getGrayRedisKey()) ?: emptySet()).filter { !it.isBlank() }.toSet()

    private fun packagingBean(tProjectRecord: TProjectRecord, grayProjectSet: Set<String>): ProjectVO {
        return ProjectVO(
                id = tProjectRecord.id,
                projectId = tProjectRecord.projectId,
                projectName = tProjectRecord.projectName,
                englishName = tProjectRecord.englishName ?: "",
                projectCode = tProjectRecord.englishName ?: "",
                projectType = tProjectRecord.projectType ?: 0,
                approvalStatus = tProjectRecord.approvalStatus ?: 0,
                approvalTime = if (tProjectRecord.approvalTime == null) {
                    ""
                } else {
                    DateTimeUtil.toDateTime(tProjectRecord.approvalTime, "yyyy-MM-dd'T'HH:mm:ssZ")
                },
                approver = tProjectRecord.approver ?: "",
                bgId = tProjectRecord.bgId?.toLong(),
                bgName = tProjectRecord.bgName ?: "",
                ccAppId = tProjectRecord.ccAppId ?: 0,
                ccAppName = tProjectRecord.ccAppName ?: "",
                centerId = tProjectRecord.centerId?.toLong() ?: 0,
                centerName = tProjectRecord.centerName ?: "",
                createdAt = DateTimeUtil.toDateTime(tProjectRecord.createdAt, "yyyy-MM-dd"),
                creator = tProjectRecord.creator ?: "",
                dataId = tProjectRecord.dataId ?: 0,
                deployType = tProjectRecord.deployType ?: "",
                deptId = tProjectRecord.deptId?.toLong() ?: 0,
                deptName = tProjectRecord.deptName ?: "",
                description = tProjectRecord.description ?: "",
                extra = tProjectRecord.extra ?: "",
                isSecrecy = tProjectRecord.isSecrecy,
                isHelmChartEnabled = tProjectRecord.isHelmChartEnabled,
                kind = tProjectRecord.kind,
                logoAddr = tProjectRecord.logoAddr ?: "",
                remark = tProjectRecord.remark ?: "",
                updatedAt = if (tProjectRecord.updatedAt == null) {
                    ""
                } else {
                    DateTimeUtil.toDateTime(tProjectRecord.updatedAt, "yyyy-MM-dd")
                },
                useBk = tProjectRecord.useBk,
                enabled = tProjectRecord.enabled ?: true,
                gray = grayProjectSet.contains(tProjectRecord.englishName),
                hybridCcAppId = tProjectRecord.hybridCcAppId,
                enableExternal = tProjectRecord.enableExternal,
                enableIdc = tProjectRecord.enableIdc,
                isOfflined = tProjectRecord.isOfflined
        )
    }


    private fun convertFile(inputStream: InputStream): File {
        val logo = Files.createTempFile("default_", ".png").toFile()

        logo.outputStream().use {
            inputStream.copyTo(it)
        }

        return logo
    }

    private fun request(request: Request, errorMessage: String): String {
//        val httpClient = okHttpClient.newBuilder().build()
        OkhttpUtils.doHttp(request).use { response ->
            //        httpClient.newCall(request).execute().use { response ->
            val responseContent = response.body()!!.string()
            if (!response.isSuccessful) {
                logger.warn("Fail to request($request) with code ${response.code()} , message ${response.message()} and response $responseContent")
                throw OperationException(errorMessage)
            }
            return responseContent
        }
    }

    private fun deleteProjectFromAuth(projectId: String, accessToken: String, retry: Boolean = true) {
        logger.warn("Deleting the project $projectId from auth")
        try {
            val url = "$authUrl/$projectId?access_token=$accessToken"
            val request = Request.Builder().url(url).delete().build()
            val responseContent = request(request, "Fail to delete the project $projectId")
            logger.info("Get the delete project $projectId response $responseContent")
            val response: Response<Any?> = objectMapper.readValue(responseContent)
            if (response.code.toInt() != 0) {
                logger.warn("Fail to delete the project $projectId with response $responseContent")
                deleteProjectFromAuth(projectId, accessToken, false)
            }
            logger.info("Finish deleting the project $projectId from auth")
        } catch (t: Throwable) {
            logger.warn("Fail to delete the project $projectId from auth", t)
            if (retry) {
                deleteProjectFromAuth(projectId, accessToken, false)
            }
        }
    }

    fun validate(
            validateType: ProjectValidateType,
            name: String,
            projectId: String? = null
    ) {
        if (name.isBlank()) {
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.NAME_EMPTY))
        }
        when (validateType) {
            ProjectValidateType.project_name -> {
                if (name.length > 12) {
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.NAME_TOO_LONG))
                }
                if (projectDao.existByProjectName(dslContext, name, projectId)) {
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NAME_EXIST))
                }
            }
            ProjectValidateType.english_name -> {
                // 2 ~ 32 ���ַ�+���֣���Сд��ĸ��ͷ
                if (name.length < 2) {
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.EN_NAME_INTERVAL_ERROR))
                }
                if (name.length > 32) {
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.EN_NAME_INTERVAL_ERROR))
                }
                if (!Pattern.matches(ENGLISH_NAME_PATTERN, name)) {
                    logger.warn("Project English Name($name) is not match")
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.EN_NAME_COMBINATION_ERROR))
                }
                if (projectDao.existByEnglishName(dslContext, name, projectId)) {
                    throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.EN_NAME_EXIST))
                }
            }
        }
    }

    fun getProjectIdInAuth(projectCode: String, accessToken: String): String? {
        try {
            val url = "$authUrl/$projectCode?access_token=$accessToken"
            logger.info("Get request url: $url")
            OkhttpUtils.doGet(url).use { resp ->
                val responseStr = resp.body()!!.string()
                logger.info("responseBody: $responseStr")
                val response: Map<String, Any> = jacksonObjectMapper().readValue(responseStr)
                return if (response["code"] as Int == 0) {
                    response["project_id"] as String
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Get project info error", e)
            throw RuntimeException("Get project info error: ${e.message}")
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(this::class.java)
        const val PROJECT_LIST = "project_list"
        const val PROJECT_CREATE = "project_create"
        const val PROJECT_UPDATE = "project_update"
    }
}