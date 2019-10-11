package com.tencent.devops.repository.service

import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.repository.pojo.Project
import com.tencent.devops.repository.pojo.enums.RepoAuthType
import com.tencent.devops.repository.pojo.oauth.GitToken
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.QueryParam

interface RepostioryScmService {

    fun getProject(
            accessToken: String,
            userId: String
    ): List<Project>

    fun getAuthUrl(
            authParamJsonStr: String
    ): String

    fun getToken(
            userId: String,
            code: String
    ): GitToken

    @ApiOperation("��ȡת����ַ")
    fun getRedirectUrl(
            redirectUrlType: String
    ): String

    @ApiOperation("ˢ���û���token")
    fun refreshToken(
            userId: String,
            accessToken: GitToken
    ): GitToken

    @ApiOperation("��ȡ�ļ�����")
    fun getSvnFileContent(
            url: String,
            userId: String,
            svnType: String,
            filePath: String,
            reversion: Long,
            credential1: String,
            credential2: String? = null
    ): String

    @ApiOperation("��ȡgit�ļ�����")
    fun getGitFileContent(
            repoName: String,
            filePath: String,
            authType: RepoAuthType?,
            token: String,
            ref: String
    ): String

    @ApiOperation("��ȡgitlab�ļ�����")
    fun getGitlabFileContent(
            repoUrl: String,
            repoName: String,
            filePath: String,
            ref: String,
            accessToken: String
    ): String

}