package com.tencent.devops.project.dao

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.model.project.tables.TServiceItem
import com.tencent.devops.model.project.tables.records.TServiceItemRecord
import com.tencent.devops.project.api.pojo.enums.ServiceItemStatusEnum
import com.tencent.devops.project.pojo.ItemCreateInfo
import com.tencent.devops.project.pojo.ItemQueryInfo
import com.tencent.devops.project.pojo.ItemUpdateInfo
import org.jooq.DSLContext
import org.jooq.Result
import org.springframework.stereotype.Repository
import org.springframework.util.StringUtils
import java.time.LocalDateTime

@Repository
class ServiceItemDao {

    fun add(dslContext: DSLContext, userId: String, info: ItemCreateInfo): String {
        val id = UUIDUtil.generate()
        with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.insertInto(
                this,
                ID,
                ITEM_CODE,
                ITEM_NAME,
                PARENT_ID,
                HTML_COMPONENT_TYPE,
                HTML_PATH,
                ICON_URL,
                PROPS,
                TOOLTIP,
                CREATOR,
                CREATE_TIME
            )
                .values(
                    id,
                    info.itemCode,
                    info.itemName,
                    info.serviceId,
                    info.UIType.name,
                    info.htmlPath,
                    info.iconUrl,
                    info.props,
                    info.tooltip,
                    userId,
                    LocalDateTime.now()
                )
                .execute()
        }
        return id
    }

    fun update(dslContext: DSLContext, itemId: String, userId: String, info: ItemUpdateInfo) {
        with(TServiceItem.T_SERVICE_ITEM) {
            val baseStep = dslContext.update(this)
            if (null != info.itemName) {
                baseStep.set(ITEM_NAME, info.itemName)
            }
            if (null != info.htmlPath) {
                baseStep.set(HTML_PATH, info.htmlPath)
            }
            if (null != info.serviceId) {
                baseStep.set(PARENT_ID, info.serviceId)
            }
            if (null != info.UIType) {
                baseStep.set(HTML_COMPONENT_TYPE, info.UIType.name)
            }
            if (null != info.iconUrl) {
                baseStep.set(ICON_URL, info.iconUrl)
            }
            if (null != info.tooltip) {
                baseStep.set(TOOLTIP, info.tooltip)
            }
            if (null != info.props) {
                baseStep.set(PROPS, info.props)
            }
            baseStep.set(MODIFIER, userId)
            baseStep.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(itemId))
                .execute()
        }
    }

    fun delete(dslContext: DSLContext, userId: String, itemId: String) {
        with(TServiceItem.T_SERVICE_ITEM) {
            val baseStep = dslContext.update(this)
            baseStep.set(ITEM_STATUS, ServiceItemStatusEnum.DELETE.name)
            baseStep.set(MODIFIER, userId)
            baseStep.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(itemId))
                .execute()
        }
    }

    fun disable(dslContext: DSLContext, userId: String, itemId: String) {
        with(TServiceItem.T_SERVICE_ITEM) {
            val baseStep = dslContext.update(this)
            baseStep.set(ITEM_STATUS, ServiceItemStatusEnum.DISABLE.name)
            baseStep.set(MODIFIER, userId)
            baseStep.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(itemId))
                .execute()
        }
    }

    fun enable(dslContext: DSLContext, userId: String, itemId: String) {
        with(TServiceItem.T_SERVICE_ITEM) {
            val baseStep = dslContext.update(this)
            baseStep.set(ITEM_STATUS, ServiceItemStatusEnum.ENABLE.name)
            baseStep.set(MODIFIER, userId)
            baseStep.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(itemId))
                .execute()
        }
    }

    fun addCount(dslContext: DSLContext, itemId: String, serviceNum: Int) {
        with(TServiceItem.T_SERVICE_ITEM) {
            val baseStep = dslContext.update(this)
            baseStep.set(SERVICE_NUM, serviceNum)
            baseStep.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(itemId))
                .execute()
        }
    }

    fun queryItem(dslContext: DSLContext, itemQueryInfo: ItemQueryInfo): Result<TServiceItemRecord>? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            val whereStep = dslContext.selectFrom(this)
            if (itemQueryInfo.itemName != null) {
                whereStep.where(ITEM_NAME.like("%${itemQueryInfo.itemName}%"))
            }

            if (itemQueryInfo.serviceId != null) {
                whereStep.where(PARENT_ID.eq(itemQueryInfo.serviceId))
            }
            whereStep.where(ITEM_STATUS.notEqual("DELETE"))
            if (itemQueryInfo.page != null && itemQueryInfo.pageSize != null) {
                whereStep.limit((itemQueryInfo.page - 1) * itemQueryInfo.pageSize, itemQueryInfo.pageSize).fetch()
            } else {
                whereStep.orderBy(UPDATE_TIME).fetch()
            }
        }
    }

    fun queryCount(dslContext: DSLContext, itemQueryInfo: ItemQueryInfo): Int? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            val whereStep = dslContext.select(this.ID.countDistinct()).from(this)
            if (itemQueryInfo.itemName != null) {
                whereStep.where(ITEM_NAME.like("%${itemQueryInfo.itemName}%"))
            }

            if (itemQueryInfo.serviceId != null) {
                whereStep.where(PARENT_ID.eq(itemQueryInfo.serviceId))
            }
            whereStep.where(ITEM_STATUS.notEqual("DELETE"))

            whereStep.fetchOne(0, Int::class.java)
        }
    }

    fun getItemById(dslContext: DSLContext, itemId: String): TServiceItemRecord? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                ID.eq(itemId)
            ).fetchOne()
        }
    }

    fun getItemByCode(dslContext: DSLContext, itemCode: String): TServiceItemRecord? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                ITEM_CODE.eq(itemCode).and(ITEM_STATUS.eq(ServiceItemStatusEnum.ENABLE.name))
            ).fetchOne()
        }
    }

    fun getItemByHtmlPath(dslContext: DSLContext, htmlPath: String): TServiceItemRecord? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                HTML_PATH.eq(htmlPath).and(ITEM_STATUS.eq(ServiceItemStatusEnum.ENABLE.name))
            ).fetchOne()
        }
    }

    fun getItemParent(dslContext: DSLContext): Result<TServiceItemRecord?> {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                PARENT_ID.isNotNull
            ).fetch()
        }
    }

    fun getItemByIds(dslContext: DSLContext, itemIds: Set<String>): Result<TServiceItemRecord?> {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                ID.`in`(itemIds)
            ).fetch()
        }
    }

    fun getItemByCodes(dslContext: DSLContext, itemCodes: Set<String>): Result<TServiceItemRecord?> {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(
                ITEM_CODE.`in`(itemCodes).and(ITEM_STATUS.eq(ServiceItemStatusEnum.ENABLE.name))
            ).fetch()
        }
    }

    fun getAllServiceItem(dslContext: DSLContext): Result<TServiceItemRecord>? {
        return with(TServiceItem.T_SERVICE_ITEM) {
            dslContext.selectFrom(this).where(ITEM_STATUS.notEqual(ServiceItemStatusEnum.DELETE.name))
                .orderBy(CREATE_TIME.desc())
                .fetch()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun convertString(str: String?): Map<String, Any> {
        return if (!StringUtils.isEmpty(str)) {
            JsonUtil.getObjectMapper().readValue(str, Map::class.java) as Map<String, Any>
        } else {
            mapOf()
        }
    }
}