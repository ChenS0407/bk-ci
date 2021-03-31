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

package com.tencent.devops.experience.service

import com.tencent.devops.artifactory.util.UrlUtil
import com.tencent.devops.common.api.enums.PlatformEnum
import com.tencent.devops.common.api.pojo.Pagination
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.experience.constant.ExperiencePublicType
import com.tencent.devops.experience.dao.ExperiencePublicDao
import com.tencent.devops.experience.pojo.index.HotCategoryParam
import com.tencent.devops.experience.pojo.index.IndexAppInfoVO
import com.tencent.devops.experience.pojo.index.IndexBannerVO
import com.tencent.devops.experience.pojo.index.NewCategoryParam
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class ExperienceIndexService @Autowired constructor(
    val experiencePublicDao: ExperiencePublicDao,
    val dslContext: DSLContext
) {
    fun banners(userId: String, page: Int, pageSize: Int, platform: Int): Result<Pagination<IndexBannerVO>> {
        val offset = (page - 1) * pageSize
        val platformStr = PlatformEnum.of(platform)?.name

        val banners = experiencePublicDao.listWithBanner(
            dslContext = dslContext,
            offset = offset,
            limit = pageSize,
            platform = platformStr
        ).map {
            IndexBannerVO(
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                bannerUrl = UrlUtil.toOuterPhotoAddr(it.bannerUrl)
            )
        }.toList()

        val hasNext = if (banners.size < pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr,
                withBanner = true
            ) > (offset + pageSize)
        }

        return Result(Pagination(hasNext, banners))
    }

    fun hots(
        userId: String,
        page: Int,
        pageSize: Int,
        platform: Int,
        includeExternalUrl: Boolean?
    ): Result<Pagination<IndexAppInfoVO>> {
        val offset = (page - 1) * pageSize
        val platformStr = PlatformEnum.of(platform)?.name
        val types = ExperiencePublicType.getIds(includeExternalUrl)

        val records = experiencePublicDao.listHot(
            dslContext = dslContext,
            offset = offset,
            limit = pageSize,
            platform = platformStr,
            types = types
        ).map {
            IndexAppInfoVO(
                type = it.type,
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                experienceName = it.experienceName,
                createTime = it.updateTime.timestampmilli(),
                size = it.size,
                logoUrl = UrlUtil.toOuterPhotoAddr(it.logoUrl),
                bundleIdentifier = it.bundleIdentifier,
                externalUrl = it.externalLink
            )
        }.toList()

        val hasNext = if (records.size < pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr
            ) > (offset + pageSize)
        }

        return Result(Pagination(hasNext, records))
    }

    fun necessary(
        userId: String,
        page: Int,
        pageSize: Int,
        platform: Int,
        includeExternalUrl: Boolean?
    ): Result<Pagination<IndexAppInfoVO>> {
        val offset = (page - 1) * pageSize
        val platformStr = PlatformEnum.of(platform)?.name
        val types = ExperiencePublicType.getIds(includeExternalUrl)

        val records = experiencePublicDao.listNecessary(
            dslContext = dslContext,
            offset = offset,
            limit = pageSize,
            platform = platformStr,
            types = types
        ).map {
            IndexAppInfoVO(
                type = it.type,
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                experienceName = it.experienceName,
                createTime = it.updateTime.timestampmilli(),
                size = it.size,
                logoUrl = UrlUtil.toOuterPhotoAddr(it.logoUrl),
                bundleIdentifier = it.bundleIdentifier,
                externalUrl = it.externalLink
            )
        }.toList()

        val hasNext = if (records.size < pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr,
                necessary = true
            ) > (offset + pageSize)
        }

        return Result(Pagination(hasNext, records))
    }

    fun newest(
        userId: String,
        page: Int,
        pageSize: Int,
        platform: Int,
        includeExternalUrl: Boolean?
    ): Result<Pagination<IndexAppInfoVO>> {
        val offset = (page - 1) * pageSize
        val platformStr = PlatformEnum.of(platform)?.name
        val types = ExperiencePublicType.getIds(includeExternalUrl)

        val records = experiencePublicDao.listNew(
            dslContext = dslContext,
            offset = offset,
            limit = pageSize,
            platform = platformStr,
            types = types
        ).map {
            IndexAppInfoVO(
                type = it.type,
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                experienceName = it.experienceName,
                createTime = it.updateTime.timestampmilli(),
                size = it.size,
                logoUrl = UrlUtil.toOuterPhotoAddr(it.logoUrl),
                bundleIdentifier = it.bundleIdentifier,
                externalUrl = it.externalLink
            )
        }.toList()

        val hasNext = if (records.size < pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr
            ) > (offset + pageSize)
        }

        return Result(Pagination(hasNext, records))
    }

    fun hotCategory(
        userId: String,
        platform: Int,
        hotCategoryParam: HotCategoryParam
    ): Result<Pagination<IndexAppInfoVO>> {
        val offset = (hotCategoryParam.page - 1) * hotCategoryParam.pageSize
        val platformStr = PlatformEnum.of(platform)?.name
        val types = ExperiencePublicType.getIds(hotCategoryParam.includeExternalUrl)

        val records = experiencePublicDao.listHot(
            dslContext = dslContext,
            offset = offset,
            limit = hotCategoryParam.pageSize,
            category = hotCategoryParam.categoryId,
            platform = platformStr,
            types = types
        ).map {
            IndexAppInfoVO(
                type = it.type,
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                experienceName = it.experienceName,
                createTime = it.updateTime.timestampmilli(),
                size = it.size,
                logoUrl = UrlUtil.toOuterPhotoAddr(it.logoUrl),
                bundleIdentifier = it.bundleIdentifier,
                externalUrl = it.externalLink
            )
        }.toList()

        val hasNext = if (records.size < hotCategoryParam.pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr,
                category = hotCategoryParam.categoryId
            ) > (offset + hotCategoryParam.pageSize)
        }

        return Result(Pagination(hasNext, records))
    }

    fun newCategory(
        userId: String,
        platform: Int,
        newCategoryParam: NewCategoryParam
    ): Result<Pagination<IndexAppInfoVO>> {
        val offset = (newCategoryParam.page - 1) * newCategoryParam.pageSize
        val platformStr = PlatformEnum.of(platform)?.name
        val types = ExperiencePublicType.getIds(newCategoryParam.includeExternalUrl)

        val records = experiencePublicDao.listNew(
            dslContext = dslContext,
            offset = offset,
            limit = newCategoryParam.pageSize,
            category = newCategoryParam.categoryId,
            platform = platformStr,
            types = types
        ).map {
            IndexAppInfoVO(
                type = it.type,
                experienceHashId = HashUtil.encodeLongId(it.recordId),
                experienceName = it.experienceName,
                createTime = it.updateTime.timestampmilli(),
                size = it.size,
                logoUrl = UrlUtil.toOuterPhotoAddr(it.logoUrl),
                bundleIdentifier = it.bundleIdentifier,
                externalUrl = it.externalLink
            )
        }.toList()

        val hasNext = if (records.size < newCategoryParam.pageSize) {
            false
        } else {
            experiencePublicDao.count(
                dslContext = dslContext,
                platform = platformStr,
                category = newCategoryParam.categoryId
            ) > (offset + newCategoryParam.pageSize)
        }

        return Result(Pagination(hasNext, records))
    }
}
