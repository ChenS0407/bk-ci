/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.service

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.enums.TaskStatusEnum
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.CsvUtil
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.service.utils.HomeHostUtil
import com.tencent.devops.process.dao.PipelineAtomReplaceBaseDao
import com.tencent.devops.process.dao.PipelineAtomReplaceItemDao
import com.tencent.devops.process.engine.dao.PipelineBuildSummaryDao
import com.tencent.devops.process.engine.dao.PipelineInfoDao
import com.tencent.devops.process.engine.dao.PipelineModelTaskDao
import com.tencent.devops.process.pojo.PipelineAtomRel
import com.tencent.devops.process.utils.KEY_PIPELINE_ID
import com.tencent.devops.process.utils.KEY_PROJECT_ID
import com.tencent.devops.store.api.common.ServiceStoreResource
import com.tencent.devops.store.pojo.atom.AtomReplaceRequest
import com.tencent.devops.store.pojo.atom.AtomReplaceRollBack
import com.tencent.devops.store.pojo.common.KEY_UPDATE_TIME
import com.tencent.devops.store.pojo.common.KEY_VERSION
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.context.config.annotation.RefreshScope
import org.springframework.stereotype.Service
import java.text.MessageFormat
import java.time.LocalDateTime
import javax.servlet.http.HttpServletResponse

@Service
@RefreshScope
class PipelineAtomService @Autowired constructor(
    private val dslContext: DSLContext,
    private val pipelineInfoDao: PipelineInfoDao,
    private val pipelineBuildSummaryDao: PipelineBuildSummaryDao,
    private val pipelineModelTaskDao: PipelineModelTaskDao,
    private val pipelineAtomReplaceBaseDao: PipelineAtomReplaceBaseDao,
    private val pipelineAtomReplaceItemDao: PipelineAtomReplaceItemDao,
    private val client: Client
) {

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineAtomService::class.java)
        private const val DEFAULT_PAGE_SIZE = 50
    }

    @Value("\${pipeline.editPath}")
    private val pipelineEditPath: String = ""

    @Value("\${pipeline.atom.maxRelQueryNum}")
    private val maxRelQueryNum: Int = 2000

    @Value("\${pipeline.atom.maxRelQueryRangeTime}")
    private val maxRelQueryRangeTime: Long = 30

    fun createReplaceAtomInfo(
        userId: String,
        projectId: String?,
        atomReplaceRequest: AtomReplaceRequest
    ): Result<String> {
        logger.info("createReplaceAtomInfo [$userId|$projectId|$atomReplaceRequest]")
        val baseId = UUIDUtil.generate()
        val fromAtomCode = atomReplaceRequest.fromAtomCode
        val toAtomCode = atomReplaceRequest.toAtomCode
        dslContext.transaction { configuration ->
            val context = DSL.using(configuration)
            pipelineAtomReplaceBaseDao.createAtomReplaceBase(
                dslContext = context,
                baseId = baseId,
                projectId = projectId,
                pipelineIdList = atomReplaceRequest.pipelineIdList,
                fromAtomCode = fromAtomCode,
                toAtomCode = toAtomCode,
                userId = userId
            )
            pipelineAtomReplaceItemDao.createAtomReplaceItem(
                dslContext = context,
                baseId = baseId,
                fromAtomCode = fromAtomCode,
                toAtomCode = toAtomCode,
                versionInfoList = atomReplaceRequest.versionInfoList,
                userId = userId
            )
        }
        return Result(baseId)
    }

    fun atomReplaceRollBack(
        userId: String,
        atomReplaceRollBack: AtomReplaceRollBack
    ): Result<Boolean> {
        logger.info("atomReplaceRollBack [$userId|$atomReplaceRollBack]")
        val baseId = atomReplaceRollBack.baseId
        val itemId = atomReplaceRollBack.itemId
        // 将任务状态更新为”待回滚“状态
        dslContext.transaction { configuration ->
            val context = DSL.using(configuration)
            pipelineAtomReplaceBaseDao.updateAtomReplaceBase(
                dslContext = context,
                baseId = baseId,
                status = TaskStatusEnum.PENDING_ROLLBACK.name,
                userId = userId
            )
            if (itemId != null) {
                pipelineAtomReplaceItemDao.updateAtomReplaceItemByItemId(
                    dslContext = context,
                    itemId = itemId,
                    status = TaskStatusEnum.PENDING_ROLLBACK.name,
                    userId = userId
                )
            } else {
                pipelineAtomReplaceItemDao.updateAtomReplaceItemByBaseId(
                    dslContext = context,
                    baseId = baseId,
                    status = TaskStatusEnum.PENDING_ROLLBACK.name,
                    userId = userId
                )
            }
        }
        return Result(true)
    }

    fun getPipelineAtomRelList(
        userId: String,
        atomCode: String,
        version: String? = null,
        startUpdateTime: String,
        endUpdateTime: String,
        page: Int = 1,
        pageSize: Int = 10
    ): Result<Page<PipelineAtomRel>?> {
        // 判断用户是否有权限查询该插件的流水线信息
        validateUserAtomPermission(atomCode, userId)
        val convertStartUpdateTime = DateTimeUtil.stringToLocalDateTime(startUpdateTime)
        val convertEndUpdateTime = DateTimeUtil.stringToLocalDateTime(endUpdateTime)
        // 校验查询时间范围跨度
        validateQueryTimeRange(convertStartUpdateTime, convertEndUpdateTime)
        // 查询使用该插件的流水线信息
        val pipelineAtomRelList =
            pipelineModelTaskDao.listByAtomCode(
                dslContext = dslContext,
                atomCode = atomCode,
                version = version,
                startUpdateTime = convertStartUpdateTime,
                endUpdateTime = convertEndUpdateTime,
                page = page,
                pageSize = pageSize
            )?.map { pipelineModelTask ->
                val pipelineId = pipelineModelTask[KEY_PIPELINE_ID] as String
                val projectId = pipelineModelTask[KEY_PROJECT_ID] as String
                val pipelineInfoRecord = pipelineInfoDao.getPipelineInfo(dslContext, pipelineId)
                val pipelineBuildSummaryRecord = pipelineBuildSummaryDao.get(dslContext, pipelineId)
                val pipelineUrl = getPipelineUrl(projectId, pipelineId)
                PipelineAtomRel(
                    pipelineUrl = pipelineUrl,
                    atomVersion = pipelineModelTask[KEY_VERSION] as String,
                    modifier = pipelineInfoRecord!!.lastModifyUser,
                    updateTime = DateTimeUtil.toDateTime(pipelineModelTask[KEY_UPDATE_TIME] as LocalDateTime),
                    executor = pipelineBuildSummaryRecord?.latestStartUser,
                    executeTime = DateTimeUtil.toDateTime(pipelineBuildSummaryRecord?.latestStartTime)
                )
            }
        val pipelineAtomRelCount = pipelineModelTaskDao.countByAtomCode(
            dslContext = dslContext,
            atomCode = atomCode,
            version = version,
            startUpdateTime = convertStartUpdateTime,
            endUpdateTime = convertEndUpdateTime
        )
        val totalPages = PageUtil.calTotalPage(pageSize, pipelineAtomRelCount)
        return Result(
            Page(
                count = pipelineAtomRelCount,
                page = page,
                pageSize = pageSize,
                totalPages = totalPages,
                records = pipelineAtomRelList ?: listOf()
            )
        )
    }

    private fun validateQueryTimeRange(
        convertStartUpdateTime: LocalDateTime,
        convertEndUpdateTime: LocalDateTime
    ) {
        val tmpTime = convertStartUpdateTime.plusDays(maxRelQueryRangeTime)
        if (convertEndUpdateTime.isAfter(tmpTime)) {
            // 超过查询时间范围则报错
            throw ErrorCodeException(
                errorCode = CommonMessageCode.ERROR_QUERY_TIME_RANGE_TOO_LARGE,
                params = arrayOf(maxRelQueryRangeTime.toString())
            )
        }
    }

    fun exportPipelineAtomRelCsv(
        userId: String,
        atomCode: String,
        version: String? = null,
        startUpdateTime: String,
        endUpdateTime: String,
        response: HttpServletResponse
    ) {
        // 判断用户是否有权限查询该插件的流水线信息
        validateUserAtomPermission(atomCode, userId)
        val convertStartUpdateTime = DateTimeUtil.stringToLocalDateTime(startUpdateTime)
        val convertEndUpdateTime = DateTimeUtil.stringToLocalDateTime(endUpdateTime)
        // 校验查询时间范围跨度
        validateQueryTimeRange(convertStartUpdateTime, convertEndUpdateTime)
        // 判断导出的流水线数量是否超过系统规定的最大值
        val pipelineAtomRelCount = pipelineModelTaskDao.countByAtomCode(
            dslContext = dslContext,
            atomCode = atomCode,
            version = version,
            startUpdateTime = convertStartUpdateTime,
            endUpdateTime = convertEndUpdateTime
        )
        if (pipelineAtomRelCount > maxRelQueryNum) {
            throw ErrorCodeException(
                errorCode = CommonMessageCode.ERROR_QUERY_NUM_TOO_BIG,
                params = arrayOf(maxRelQueryNum.toString())
            )
        }
        val dataList = mutableListOf<Array<String?>>()
        var page = 1
        do {
            val pipelineAtomRelList = pipelineModelTaskDao.listByAtomCode(
                dslContext = dslContext,
                atomCode = atomCode,
                version = version,
                startUpdateTime = convertStartUpdateTime,
                endUpdateTime = convertEndUpdateTime,
                page = page,
                pageSize = DEFAULT_PAGE_SIZE
            )
            val pageDataList = mutableListOf<Array<String?>>()
            val pagePipelineIdList = mutableListOf<String>()
            pipelineAtomRelList?.forEach { pipelineAtomRel ->
                val pipelineId = pipelineAtomRel[KEY_PIPELINE_ID] as String
                val projectId = pipelineAtomRel[KEY_PROJECT_ID] as String
                pagePipelineIdList.add(pipelineId)
                val dataArray = arrayOfNulls<String>(6)
                dataArray[0] = getPipelineUrl(projectId, pipelineId)
                dataArray[1] = pipelineAtomRel[KEY_VERSION] as String
                dataArray[3] = DateTimeUtil.toDateTime(pipelineAtomRel[KEY_UPDATE_TIME] as LocalDateTime)
                pageDataList.add(dataArray)
            }
            // 查询流水线基本信息，结果集按照查询流水线ID的顺序排序
            val pagePipelineInfoRecords = pipelineInfoDao.listOrderInfoByPipelineIds(dslContext, pagePipelineIdList)
            for (index in pagePipelineIdList.indices) {
                val dataArray = pageDataList[index]
                val pipelineInfoRecord = pagePipelineInfoRecords[index]
                dataArray[2] = pipelineInfoRecord.lastModifyUser
            }
            // 查询流水线汇总信息，结果集按照查询流水线ID的顺序排序
            val pagePipelineSummaryRecords =
                pipelineBuildSummaryDao.listOrderSummaryByPipelineIds(dslContext, pagePipelineIdList)
            for (index in pagePipelineIdList.indices) {
                val dataArray = pageDataList[index]
                val pipelineSummaryRecord = pagePipelineSummaryRecords[index]
                dataArray[4] = pipelineSummaryRecord.latestStartUser
                dataArray[5] = DateTimeUtil.toDateTime(pipelineSummaryRecord.latestStartTime)
            }
            dataList.addAll(pageDataList)
            page++
        } while (pipelineAtomRelList?.size == DEFAULT_PAGE_SIZE)
        val headers = arrayOf("流水线链接", "版本", "最近修改人", "最近修改时间", "最近执行人", "最近执行时间")
        val bytes = CsvUtil.writeCsv(headers, dataList)
        CsvUtil.setCsvResponse(atomCode, bytes, response)
    }

    private fun getPipelineUrl(projectId: String, pipelineId: String): String {
        val mf = MessageFormat(pipelineEditPath)
        val convertPath = mf.format(arrayOf(projectId, pipelineId))
        return "${HomeHostUtil.innerServerHost()}/$convertPath"
    }

    private fun validateUserAtomPermission(atomCode: String, userId: String) {
        val validateResult =
            client.get(ServiceStoreResource::class).isStoreMember(atomCode, StoreTypeEnum.ATOM, userId)
        if (validateResult.isNotOk()) {
            throw ErrorCodeException(
                errorCode = validateResult.status.toString(),
                defaultMessage = validateResult.message
            )
        } else if (validateResult.isOk() && validateResult.data == false) {
            throw ErrorCodeException(
                errorCode = CommonMessageCode.PERMISSION_DENIED,
                params = arrayOf(atomCode)
            )
        }
    }
}
