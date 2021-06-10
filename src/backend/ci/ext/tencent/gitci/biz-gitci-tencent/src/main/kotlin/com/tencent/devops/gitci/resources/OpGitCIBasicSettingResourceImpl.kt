package com.tencent.devops.gitci.resources

import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.gitci.api.OpGitCIBasicSettingResource
import com.tencent.devops.gitci.pojo.v2.GitCIBasicSetting
import com.tencent.devops.gitci.v2.service.GitCIBasicSettingService
import org.springframework.beans.factory.annotation.Autowired

@RestResource
class OpGitCIBasicSettingResourceImpl @Autowired constructor(
    private val gitCIBasicSettingService: GitCIBasicSettingService
) : OpGitCIBasicSettingResource {

    override fun save(userId: String, gitCIBasicSetting: GitCIBasicSetting): Result<Boolean> {
        return Result(gitCIBasicSettingService.saveGitCIConf(userId, gitCIBasicSetting))
    }
}
