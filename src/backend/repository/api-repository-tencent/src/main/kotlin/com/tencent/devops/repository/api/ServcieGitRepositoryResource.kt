package com.tencent.devops.repository.api

import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID_DEFAULT_VALUE
import com.tencent.devops.common.api.enums.RepositoryType
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.repository.pojo.RepositoryInfo
import com.tencent.devops.repository.pojo.enums.GitAccessLevelEnum
import com.tencent.devops.repository.pojo.enums.TokenTypeEnum
import com.tencent.devops.repository.pojo.enums.VisibilityLevelEnum
import com.tencent.devops.repository.pojo.git.GitProjectInfo
import com.tencent.devops.repository.pojo.git.UpdateGitProjectInfo
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.*
import javax.ws.rs.core.MediaType

@Api(tags = ["SERVICE_REPOSITORY"], description = "����-git�������Դ")
@Path("/service/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ServcieGitRepositoryResource {

	@ApiOperation("����git�����")
	@POST
	@Path("/git/create/repository")
	fun createGitCodeRepository(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam("��Ŀ����", required = true)
			@QueryParam("projectCode")
			projectCode: String,
			@ApiParam("���������", required = true)
			@QueryParam("repositoryName")
			repositoryName: String,
			@ApiParam("��������·��", required = true)
			@QueryParam("sampleProjectPath")
			sampleProjectPath: String,
			@ApiParam(value = "�����ռ�ID", required = false)
			@QueryParam("namespaceId")
			namespaceId: Int?,
			@ApiParam(value = "��Ŀ���ӷ�Χ", required = false)
			@QueryParam("visibilityLevel")
			visibilityLevel: VisibilityLevelEnum?,
			@ApiParam(value = "token���� 1��oauth 2:privateKey", required = true)
			@QueryParam("tokenType")
			tokenType: TokenTypeEnum
	): Result<RepositoryInfo?>

	@ApiOperation("����git�������Ϣ")
	@PUT
	@Path("/git/update/repository")
	fun updateGitCodeRepository(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam(value = "�ֿ�id", required = true)
			@QueryParam("repoId")
			repoId: String,
			@ApiParam("����������Ϣ", required = true)
			updateGitProjectInfo: UpdateGitProjectInfo,
			@ApiParam(value = "token���� 1��oauth 2:privateKey", required = true)
			@QueryParam("tokenType")
			tokenType: TokenTypeEnum
	): Result<Boolean>

	@ApiOperation("Ϊ��Ŀ��Ա��������Ȩ��")
	@POST
	@Path("/git/repository/members/add")
	fun addGitProjectMember(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam("���ӵ��û��б�", required = true)
			@QueryParam("userIdList")
			userIdList: List<String>,
			@ApiParam(value = "�ֿ�id", required = true)
			@QueryParam("repoId")
			repoId: String,
			@ApiParam(value = "git����Ȩ��", required = true)
			@QueryParam("gitAccessLevel")
			gitAccessLevel: GitAccessLevelEnum,
			@ApiParam(value = "token���� 1��oauth 2:privateKey", required = true)
			@QueryParam("tokenType")
			tokenType: TokenTypeEnum
	): Result<Boolean>

	@ApiOperation("ɾ����Ŀ��Ա�Ĵ����Ȩ��")
	@DELETE
	@Path("/git/repository/members/delete")
	fun deleteGitProjectMember(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam("ɾ�����û��б�", required = true)
			@QueryParam("userIdList")
			userIdList: List<String>,
			@ApiParam(value = "�ֿ�id", required = true)
			@QueryParam("repoId")
			repoId: String,
			@ApiParam(value = "token���� 1��oauth 2:privateKey", required = true)
			@QueryParam("tokenType")
			tokenType: TokenTypeEnum
	): Result<Boolean>

	@ApiOperation("���´�����û���Ϣ")
	@PUT
	@Path("/git/repository/user/info/update")
	fun updateRepositoryUserInfo(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam("��Ŀ����", required = true)
			@QueryParam("projectCode")
			projectCode: String,
			@ApiParam("�����HashId", required = true)
			@QueryParam("repositoryHashId")
			repositoryHashId: String
	): Result<Boolean>

	@ApiOperation("����ĿǨ�Ƶ�ָ����Ŀ����")
	@GET
	@Path("/git/move/repository/group")
	fun moveGitProjectToGroup(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam(value = "��Ŀ�����", required = false)
			@QueryParam("groupCode")
			groupCode: String?,
			@ApiParam(value = "�ֿ�id", required = true)
			@QueryParam("repoId")
			repoId: String,
			@ApiParam(value = "token���� 1��oauth 2:privateKey", required = true)
			@QueryParam("tokenType")
			tokenType: TokenTypeEnum
	): Result<GitProjectInfo?>

	@ApiOperation("��ȡ����ֿⵥ���ļ�����")
	@GET
	@Path("/{repoId}/getFileContent")
	fun getFileContent(
			@ApiParam(value = "�ֿ�id")
			@PathParam("repoId")
			repoId: String,
			@ApiParam(value = "�ļ�·��")
			@QueryParam("filePath")
			filePath: String,
			@ApiParam(value = "�汾�ţ�svn��")
			@QueryParam("reversion")
			reversion: String?,
			@ApiParam(value = "��֧��git��")
			@QueryParam("branch")
			branch: String?,
			@ApiParam("�������������", required = true)
			@QueryParam("repositoryType")
			repositoryType: RepositoryType?
	): Result<String>

	@ApiOperation("ɾ�������")
	@DELETE
	@Path("/{projectId}/{repositoryHashId}")
	fun delete(
			@ApiParam(value = "�û�ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
			@HeaderParam(AUTH_HEADER_USER_ID)
			userId: String,
			@ApiParam("��ĿID", required = true)
			@PathParam("projectId")
			projectId: String,
			@ApiParam("������ϣID", required = true)
			@PathParam("repositoryHashId")
			repositoryHashId: String
	): Result<Boolean>
}