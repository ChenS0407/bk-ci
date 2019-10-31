package com.tencent.devops.project.service.impl

import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.gray.Gray
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.model.project.tables.records.TProjectRecord
import com.tencent.devops.project.ProjectInfoResponse
import com.tencent.devops.project.constant.ProjectMessageCode
import com.tencent.devops.project.dao.ProjectDao
import com.tencent.devops.project.dao.ProjectLabelRelDao
import com.tencent.devops.project.pojo.OpGrayProject
import com.tencent.devops.project.pojo.OpProjectUpdateInfoRequest
import com.tencent.devops.project.pojo.Result
import com.tencent.devops.project.service.OpProjectService
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.util.CollectionUtils

abstract class AbsOpProjectServiceImpl @Autowired constructor(
    private val dslContext: DSLContext,
    private val projectDao: ProjectDao,
    private val projectLabelRelDao: ProjectLabelRelDao,
    private val redisOperation: RedisOperation,
    private val gray: Gray
): OpProjectService {

    override fun listGrayProject(): Result<OpGrayProject> {
        // ��redis�л�ȡ�Ҷ���Ŀ�б�
        return Result(OpGrayProject(grayProjectSet().toList()))
    }

    override fun setGrayProject(projectCodeList: List<String>, operateFlag: Int): Boolean {
        logger.info("the projectCodeList is: $projectCodeList,operateFlag is:$operateFlag")
        // ʹ��set���ϣ�ȥ���ظ�Ԫ�أ������ύ����Ŀ�б�
        for (item in projectCodeList) {
            if (1 == operateFlag) {
                redisOperation.addSetValue(gray.getGrayRedisKey(), item) // �����ĿΪ�Ҷ���Ŀ
            } else if (2 == operateFlag) {
                redisOperation.removeSetMember(gray.getGrayRedisKey(), item) // ȡ����ĿΪ�Ҷ���Ŀ
            }
        }
        val projectCodeSet = grayProjectSet()
        logger.info("the set projectSet is: $projectCodeSet")
        return true
    }

    override fun updateProjectFromOp(userId: String, accessToken: String, projectInfoRequest: OpProjectUpdateInfoRequest): Int {
        logger.info("the projectInfoRequest is: $projectInfoRequest")
        val projectId = projectInfoRequest.projectId
        val dbProjectRecord = projectDao.get(dslContext, projectId)
        if (dbProjectRecord == null) {
            logger.warn("The project $projectId is not exist")
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NOT_EXIST))
        }
        // �ж���Ŀ�ǲ�����˵����
        var flag = false
        if (1 == dbProjectRecord.approvalStatus && (2 == projectInfoRequest.approvalStatus || 3 == projectInfoRequest.approvalStatus)) {
            flag = true
            projectInfoRequest.approver = projectInfoRequest.approver
            projectInfoRequest.approvalTime = System.currentTimeMillis()
        } else {
            projectInfoRequest.approver = dbProjectRecord.approver
            projectInfoRequest.approvalTime = dbProjectRecord.approvalTime?.timestampmilli()
        }
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)

            try {
                projectDao.updateProjectFromOp(transactionContext, projectInfoRequest)
            } catch (ignored: DuplicateKeyException) {
                logger.warn("Duplicate project $projectInfoRequest", ignored)
                throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PROJECT_NAME_EXIST))
            }
            // �Ƚ����Ŀ���ǩ�Ĺ�����ϵ��Ȼ���ٴ��½�������֮��Ĺ�ϵ
            projectLabelRelDao.deleteByProjectId(transactionContext, projectId)
            val labelIdList = projectInfoRequest.labelIdList
            if (!CollectionUtils.isEmpty(labelIdList)) projectLabelRelDao.batchAdd(
                    transactionContext,
                    projectId,
                    labelIdList!!
            )

        }
        return if (!flag) {
            0 // ���²���
        } else {
            return when {
                2 == projectInfoRequest.approvalStatus -> 1 // ����ͨ��
                3 == projectInfoRequest.approvalStatus -> 2 // ����
                else -> 0
            }
        }
    }

    override fun getProjectList(projectName: String?, englishName: String?, projectType: Int?, isSecrecy: Boolean?, creator: String?, approver: String?, approvalStatus: Int?, offset: Int, limit: Int, grayFlag: Boolean): Result<Map<String, Any?>?> {
        val dataObj = mutableMapOf<String, Any?>()

        val projectCodeSet = if (grayFlag) {
            redisOperation.getSetMembers(gray.getGrayRedisKey())
        } else {
            null
        }
        val projectInfos = projectDao.getProjectList(
                dslContext = dslContext,
                projectName = projectName,
                englishName = englishName,
                projectType = projectType,
                isSecrecy = isSecrecy,
                creator = creator,
                approver = approver,
                approvalStatus = approvalStatus,
                offset = offset,
                limit = limit,
                grayFlag = grayFlag,
                englishNames = projectCodeSet
        )
        val totalCount = projectDao.getProjectCount(
                dslContext = dslContext,
                projectName = projectName,
                englishName = englishName,
                projectType = projectType,
                isSecrecy = isSecrecy,
                creator = creator,
                approver = approver,
                approvalStatus = approvalStatus,
                grayFlag = grayFlag,
                englishNames = projectCodeSet
        )
        val dataList = mutableListOf<ProjectInfoResponse>()
        val grayProjectSet = grayProjectSet()
        for (i in projectInfos.indices) {
            val projectData = projectInfos[i]
            val projectInfo = getProjectInfoResponse(projectData, grayProjectSet)
            dataList.add(projectInfo)
        }
        dataObj["projectList"] = dataList
        dataObj["count"] = totalCount
        return Result(dataObj)
    }

    override fun getProjectCount(projectName: String?, englishName: String?, projectType: Int?, isSecrecy: Boolean?, creator: String?, approver: String?, approvalStatus: Int?, grayFlag: Boolean): Result<Int> {
        return Result(
                data = projectDao.getProjectCount(
                        dslContext = dslContext,
                        projectName = projectName,
                        englishName = englishName,
                        projectType = projectType,
                        isSecrecy = isSecrecy,
                        creator = creator,
                        approver = approver,
                        approvalStatus = approvalStatus,
                        grayFlag = grayFlag,
                        englishNames = grayProjectSet()
                )
        )
    }

    fun grayProjectSet() =
            (redisOperation.getSetMembers(gray.getGrayRedisKey()) ?: emptySet()).filter { !it.isBlank() }.toSet()

    private fun getProjectInfoResponse(projectData: TProjectRecord, grayProjectSet: Set<String>): ProjectInfoResponse {
        return ProjectInfoResponse(
                projectId = projectData.projectId,
                projectName = projectData.projectName,
                projectEnglishName = projectData.englishName,
                creatorBgName = projectData.creatorBgName,
                creatorDeptName = projectData.creatorDeptName,
                creatorCenterName = projectData.creatorCenterName,
                bgId = projectData.bgId,
                bgName = projectData.bgName,
                deptId = projectData.deptId,
                deptName = projectData.deptName,
                centerId = projectData.centerId,
                centerName = projectData.centerName,
                projectType = projectData.projectType,
                approver = projectData.approver,
                approvalTime = projectData.approvalTime?.timestampmilli(),
                approvalStatus = projectData.approvalStatus,
                secrecyFlag = projectData.isSecrecy,
                creator = projectData.creator,
                createdAtTime = projectData.createdAt.timestampmilli(),
                ccAppId = projectData.ccAppId,
                useBk = projectData.useBk,
                offlinedFlag = projectData.isOfflined,
                kind = projectData.kind,
                enabled = projectData.enabled ?: true,
                grayFlag = grayProjectSet.contains(projectData.englishName),
                hybridCCAppId = projectData.hybridCcAppId,
                enableExternal = projectData.enableExternal,
                enableIdc = projectData.enableIdc
        )
    }

    companion object {
        val logger = LoggerFactory.getLogger(this:: class.java)
    }
}