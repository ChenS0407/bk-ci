package com.tencent.devops.experience.dao

import com.tencent.devops.model.experience.tables.TExperienceDownloadDetail
import com.tencent.devops.model.experience.tables.TExperiencePublic
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Result
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ExperienceDownloadDetailDao {
    @SuppressWarnings("LongParameterList")
    fun create(
        dslContext: DSLContext,
        userId: String,
        recordId: Long,
        projectId: String,
        bundleIdentifier: String,
        platform: String
    ) {
        with(TExperienceDownloadDetail.T_EXPERIENCE_DOWNLOAD_DETAIL) {
            val now = LocalDateTime.now()
            dslContext.insertInto(
                this,
                USER_ID,
                RECORD_ID,
                PROJECT_ID,
                BUNDLE_IDENTIFIER,
                PLATFORM,
                CREATE_TIME,
                UPDATE_TIME
            ).values(
                userId,
                recordId,
                projectId,
                bundleIdentifier,
                platform,
                now,
                now
            ).execute()
        }
    }

    fun countForHot(
        dslContext: DSLContext,
        projectId: String,
        bundleIdentifier: String,
        platform: String,
        hotDaysAgo: LocalDateTime
    ): Int {
        with(TExperienceDownloadDetail.T_EXPERIENCE_DOWNLOAD_DETAIL) {
            return dslContext.selectCount()
                .from(this)
                .where(PROJECT_ID.eq(projectId))
                .and(BUNDLE_IDENTIFIER.eq(bundleIdentifier))
                .and(PLATFORM.eq(platform))
                .and(CREATE_TIME.gt(hotDaysAgo))
                .fetchAny()?.value1() ?: 0
        }
    }

    fun listIdsForPublic(dslContext: DSLContext, limit: Int): Result<Record1<Long>> {
        val p = TExperiencePublic.T_EXPERIENCE_PUBLIC.`as`("p")
        val d = TExperienceDownloadDetail.T_EXPERIENCE_DOWNLOAD_DETAIL.`as`("d")
        val join = p.leftJoin(d).on(
            p.PLATFORM.eq(d.PLATFORM)
                .and(p.PROJECT_ID.eq(d.PROJECT_ID))
                .and(p.BUNDLE_IDENTIFIER.eq(d.BUNDLE_IDENTIFIER))
        )
        return dslContext.select(p.RECORD_ID).from(join)
            .where(p.ONLINE.eq(true))
            .and(p.END_DATE.gt(LocalDateTime.now()))
            .orderBy(d.UPDATE_TIME.desc()).limit(limit)
            .fetch()
    }
}
