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

package com.tencent.devops.gitci.v2.template

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.YamlUtil
import com.tencent.devops.common.ci.v2.PreTemplateScriptBuildYaml
import com.tencent.devops.common.ci.v2.utils.ScriptYmlUtils
import com.tencent.devops.gitci.v2.template.pojo.TemplateGraph
import org.junit.Test

import org.springframework.core.io.ClassPathResource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class YamlTemplateTest {

    @Test
    fun testAllTemplate() {
        replace("all.yml")
    }

    @Test
    fun testExtendsTemplate() {
        replace("extends.yml")
    }

    private fun replace(testYaml: String) {
        val classPathResource = ClassPathResource(testYaml)
        val inputStream: InputStream = classPathResource.inputStream
        val isReader = InputStreamReader(inputStream)

        val reader = BufferedReader(isReader)
        val sb = StringBuffer()
        var str: String?
        while (reader.readLine().also { str = it } != null) {
            sb.append(str).append("\n")
        }

        val yaml = ScriptYmlUtils.formatYaml(sb.toString())
        val preTemplateYamlObject = YamlUtil.getObjectMapper().readValue(yaml, PreTemplateScriptBuildYaml::class.java)
        val preScriptBuildYaml = YamlTemplate(
        yamlObject = preTemplateYamlObject,
        filePath = testYaml,
        triggerUserId = "ruotiantang",
        triggerProjectId = 580280,
        triggerToken = "",
        triggerRef = "master",
        repo = null,
        repoTemplateGraph = TemplateGraph(),
        sourceProjectId = 580280,
        getTemplateMethod = ::getTestTemplate
        ).replace()
        val aa = ScriptYmlUtils.normalizeGitCiYaml(preScriptBuildYaml, "")
        println(JsonUtil.toJson(aa))
/*        val result = YamlCommonUtils.toYamlNotNull(
            YamlTemplate(
                yamlObject = preTemplateYamlObject,
                filePath = testYaml,
                triggerUserId = "ruotiantang",
                triggerProjectId = 580280,
                triggerToken = "",
                triggerRef = "master",
                repo = null,
                repoTemplateGraph = TemplateGraph(),
                sourceProjectId = 580280,
                getTemplateMethod = ::getTestTemplate
            ).replace()
        )
        println(
            result
        )*/
    }

    private fun getTestTemplate(
        token: String?,
        gitProjectId: Long,
        targetRepo: String?,
        ref: String,
        personalAccessToken: String?,
        fileName: String
    ): String {
        val newPath = if (targetRepo == null) {
            "templates/$fileName"
        } else {
            "templates/$targetRepo/templates/$fileName"
        }
        val classPathResource = ClassPathResource(newPath)
        val inputStream: InputStream = classPathResource.inputStream
        val isReader = InputStreamReader(inputStream)

        val reader = BufferedReader(isReader)
        val sb = StringBuffer()
        var str: String?
        while (reader.readLine().also { str = it } != null) {
            sb.append(str).append("\n")
        }
        inputStream.close()
        return sb.toString()
    }
}
