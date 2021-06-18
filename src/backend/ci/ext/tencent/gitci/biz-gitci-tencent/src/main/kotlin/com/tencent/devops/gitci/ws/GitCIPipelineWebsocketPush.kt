package com.tencent.devops.gitci.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.event.annotation.Event
import com.tencent.devops.common.event.dispatcher.pipeline.mq.MQ
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.websocket.dispatch.message.NotifyMessage
import com.tencent.devops.common.websocket.dispatch.message.SendMessage
import com.tencent.devops.common.websocket.dispatch.push.WebsocketPush
import com.tencent.devops.common.websocket.pojo.NotifyPost
import com.tencent.devops.common.websocket.pojo.WebSocketType
import com.tencent.devops.common.websocket.utils.RedisUtlis

@Event(exchange = MQ.EXCHANGE_WEBSOCKET_TMP_FANOUT, routeKey = MQ.ROUTE_WEBSOCKET_TMP_EVENT)
class GitCIPipelineWebsocketPush (
    val pipelineId: String,
    val projectId: String,
    override val userId: String,
    override val pushType: WebSocketType,
    override val redisOperation: RedisOperation,
    override val objectMapper: ObjectMapper,
    override var page: String?,
    override var notifyPost: NotifyPost
): WebsocketPush(userId, pushType, redisOperation, objectMapper, page, notifyPost) {

    override fun findSession(page: String): List<String>? {
        val userSessionList = RedisUtlis.getSessionIdByUserId(redisOperation, userId)
        if (userSessionList.isNullOrEmpty()) {
            return emptyList()
        }
        return userSessionList.split(",")
    }

    override fun buildMqMessage(): SendMessage? {
        return NotifyMessage(
            buildId = null,
            pipelineId = pipelineId,
            projectId = projectId,
            userId = userId,
            sessionList = findSession(page ?: "") ?: emptyList(),
            page = page,
            notifyPost = notifyPost
        )
    }

    override fun buildNotifyMessage(message: SendMessage) {
        return
    }
}
