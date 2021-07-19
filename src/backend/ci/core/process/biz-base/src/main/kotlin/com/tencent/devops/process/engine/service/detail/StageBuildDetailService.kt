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

package com.tencent.devops.process.engine.service.detail

import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.process.dao.BuildDetailDao
import com.tencent.devops.process.engine.dao.PipelineBuildDao
import com.tencent.devops.process.engine.pojo.PipelineBuildStageControlOption
import com.tencent.devops.process.pojo.BuildStageStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Service

@Suppress("LongParameterList", "MagicNumber")
@Service
class StageBuildDetailService(
    dslContext: DSLContext,
    pipelineBuildDao: PipelineBuildDao,
    buildDetailDao: BuildDetailDao,
    pipelineEventDispatcher: PipelineEventDispatcher,
    redisOperation: RedisOperation
) : BaseBuildDetailService(
    dslContext,
    pipelineBuildDao,
    buildDetailDao,
    pipelineEventDispatcher,
    redisOperation
) {

    fun updateStageStatus(buildId: String, stageId: String, buildStatus: BuildStatus): List<BuildStageStatus> {
        logger.info("[$buildId]|update_stage_status|stageId=$stageId|status=$buildStatus")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = buildStatus.name
                    if (buildStatus.isRunning() && stage.startEpoch == null) {
                        stage.startEpoch = System.currentTimeMillis()
                    } else if (buildStatus.isFinish() && stage.startEpoch != null) {
                        stage.elapsed = System.currentTimeMillis() - stage.startEpoch!!
                    }
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    fun stageSkip(buildId: String, stageId: String): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_skip|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.SKIP.name
                    stage.containers.forEach {
                        it.status = BuildStatus.SKIP.name
                    }
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    fun stagePause(
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_pause|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.PAUSE.name
                    stage.reviewStatus = BuildStatus.REVIEWING.name
                    stage.stageControlOption = controlOption.stageControlOption
                    stage.startEpoch = System.currentTimeMillis()
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.STAGE_SUCCESS)
        return allStageStatus ?: emptyList()
    }

    fun stageCancel(
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ) {
        logger.info("[$buildId]|stage_cancel|stageId=$stageId")
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = ""
                    stage.reviewStatus = BuildStatus.REVIEW_ABORT.name
                    stage.stageControlOption = controlOption.stageControlOption
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.STAGE_SUCCESS)
    }

    fun stageReview(
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ) {
        logger.info("[$buildId]|stage_review|stageId=$stageId")
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.stageControlOption = controlOption.stageControlOption
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun stageStart(
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_start|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.QUEUE.name
                    stage.reviewStatus = BuildStatus.REVIEW_PROCESSED.name
                    stage.stageControlOption = controlOption.stageControlOption
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    private fun fetchHistoryStageStatus(model: Model): List<BuildStageStatus> {
        // 更新Stage状态至BuildHistory
        return model.stages.map {
            BuildStageStatus(
                stageId = it.id!!,
                name = it.name ?: it.id!!,
                status = it.status,
                startEpoch = it.startEpoch,
                elapsed = it.elapsed
            )
        }
    }
}
