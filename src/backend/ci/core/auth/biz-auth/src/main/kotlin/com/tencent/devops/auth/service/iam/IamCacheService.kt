package com.tencent.devops.auth.service.iam

import com.google.common.cache.CacheBuilder
import com.tencent.bk.sdk.iam.dto.action.ActionDTO
import com.tencent.bk.sdk.iam.dto.expression.ExpressionDTO
import com.tencent.bk.sdk.iam.service.PolicyService
import com.tencent.devops.auth.common.Constants.ALL_ACTION
import com.tencent.devops.auth.common.Constants.PROJECT_VIEW
import com.tencent.devops.common.auth.utils.AuthUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class IamCacheService @Autowired constructor(
    @Autowired(required = false)
    val policyService: PolicyService?
) {

    // 用户-管理员项目 缓存， 10分钟有效时间
    private val projectManager = CacheBuilder.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build<String, List<String>>()

    // 用户-project_view项目 缓存， 10分钟有效时间
    private val projectViewCache = CacheBuilder.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build<String, List<String>>()

    // 用户 -- action下策略
    private val userExpressionCache = CacheBuilder.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, ExpressionDTO>()

    // 用户 -- resource下action
    private val userActionCache = CacheBuilder.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, String>()

    // 通过all_action 判断是否为项目管理员, 优先查缓存, 缓存时效10分钟
    fun checkProjectManager(userId: String, projectCode: String): Boolean {
        if (projectManager.getIfPresent(userId) != null) {
            if (projectManager.getIfPresent(userId)!!.contains(projectCode)) {
                return true
            }
        }
        val projectCodes = getProjectIamData(ALL_ACTION, userId)
        if (projectCodes.isNullOrEmpty()) {
            return false
        }
        projectManager.put(userId, projectCodes)
        return projectCodes.contains(projectCode)
    }

    fun checkProjectView(userId: String, projectCode: String): Boolean {
        if (projectViewCache.getIfPresent(userId) != null) {
            if (projectViewCache.getIfPresent(userId)!!.contains(projectCode)) {
                return true
            }
        }
        val projectCodes = getProjectIamData(PROJECT_VIEW, userId)
        if (projectCode.isNullOrEmpty()) {
            return false
        }
        projectViewCache.put(userId, projectCodes)
        return projectCodes.contains(projectCode)
    }

    fun getUserExpression(userId: String, action: String, resourceType: String): ExpressionDTO? {
        val cacheKey = userId + action
        // 优先从缓存内获取
        if (userExpressionCache.getIfPresent(cacheKey) != null) {
            return userExpressionCache.getIfPresent(cacheKey)
        }

        val actionDto = ActionDTO()
        actionDto.id = action
        val expression = policyService!!.getPolicyByAction(userId, actionDto, null) ?: return null
        userExpressionCache.put(cacheKey, expression)
        // 记录资源下有缓存的action
        refreshUserAction(userId, action, resourceType)
        return expression
    }

    fun refreshUserAction(userId: String, action: String, resourceType: String) {
        val cacheKey = userId + resourceType
        if (userActionCache.getIfPresent(cacheKey) == null) {
            userActionCache.put(cacheKey, action)
        } else {
            val actions = userActionCache.getIfPresent(cacheKey)
            var newAction = action
            actions!!.split(",").forEach {
                val expressionCacheKey = userId + it
                if (userExpressionCache.getIfPresent(expressionCacheKey) != null) {
                    newAction += ",$it"
                }
            }
            userActionCache.put(cacheKey, newAction)
        }
    }

    // 新增场景下, 需要刷新缓存,否则新建实例后,无法拉取到
    fun refreshUserExpression(userId: String, resourceType: String) {
        val cacheKey = userId + resourceType
        if (userActionCache.getIfPresent(cacheKey) == null) {
            return
        }
        val userExpressionActions = userActionCache.getIfPresent(cacheKey)
        userExpressionActions!!.forEach { action ->
            val actionDto = ActionDTO()
            actionDto.id = action.toString()
            val expression = policyService!!.getPolicyByAction(userId, actionDto, null) ?: return
            userExpressionCache.put(cacheKey, expression)
        }
        return
    }

    private fun getProjectIamData(action: String, userId: String): List<String> {
        val managerActionDto = ActionDTO()
        managerActionDto.id = action
        val actionPolicyDTO = policyService!!.getPolicyByAction(userId, managerActionDto, null)
            ?: return emptyList()
        logger.info("[IAM] getIamData actionPolicyDTO $actionPolicyDTO")
        return AuthUtils.getProjects(actionPolicyDTO)
    }

    companion object {
        val logger = LoggerFactory.getLogger(IamCacheService::class.java)
    }
}
