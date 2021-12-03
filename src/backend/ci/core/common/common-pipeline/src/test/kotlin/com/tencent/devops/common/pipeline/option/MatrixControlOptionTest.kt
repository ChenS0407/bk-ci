package com.tencent.devops.common.pipeline.option

import com.tencent.devops.common.api.util.YamlUtil
import org.junit.Assert
import org.junit.Test

internal class MatrixControlOptionTest {

    /**
     * 用于比对结果的传统笛卡尔乘积算法:
     * 原二维数组[input], 通过乘积转化后的数组[output],
     * 层级参数[layer], 当前操作数组[currentList]
     */
    private fun descartes(
        input: List<List<String>>,
        output: MutableList<List<String>>,
        layer: Int, currentList: List<String>
    ) {
        if (layer < input.size - 1) {
            if (input[layer].isEmpty()) {
                descartes(input, output, layer + 1, currentList)
            } else {
                for (i in input[layer].indices) {
                    val list: MutableList<String> = ArrayList(currentList)
                    list.add(input[layer][i])
                    descartes(input, output, layer + 1, list)
                }
            }
        } else if (layer == input.size - 1) {
            if (input[layer].isEmpty()) {
                output.add(currentList)
            } else {
                for (i in input[layer].indices) {
                    val list: MutableList<String> = ArrayList(currentList)
                    list.add(input[layer][i])
                    output.add(list)
                }
            }
        }
    }

    @Test
    fun calculateValueMatrix() {
        val matrixControlOption = MatrixControlOption(
            // 2*3*3 = 18
            strategyStr = """
                    os: [docker,macos]
                    var1: [a,b,c]
                    var2: [1,2,3]
                """,
            // +2
            includeCaseStr = YamlUtil.toYaml(
                listOf(
                    mapOf(
                        "os" to "docker",
                        "var1" to "d",
                        "var2" to "0"
                    ),
                    mapOf(
                        "os" to "macos",
                        "os" to "d",
                        "var2" to "4"
                    ),
                    // +0 重复值不加入
                    mapOf(
                        "os" to "docker",
                        "var1" to "a",
                        "var2" to "1"
                    ),
                )
            ),
            // -1
            excludeCaseStr = YamlUtil.toYaml(
                listOf(
                    mapOf(
                        "os" to "docker",
                        "var1" to "a",
                        "var2" to "1"
                    )
                )
            ),
            totalCount = 10, // 3*3 + 2 - 1
            runningCount = 1,
            fastKill = true,
            maxConcurrency = 50
        )
        val contextCase = matrixControlOption.getAllContextCase()
        println(contextCase.size)
        contextCase.forEachIndexed { index, map ->
            println("$index: $map")
        }
        Assert.assertEquals(contextCase.size, 19)
    }

    @Test
    fun convertStrategyYaml() {
        val matrixControlOption = MatrixControlOption(
            strategyStr = """
                    os: [docker,macos]
                    var1: [a,b,c]
                    var2: [1,2,3]
                """,
            includeCaseStr = YamlUtil.toYaml(listOf(mapOf("var1" to "a"), mapOf("var2" to "2"))),
            excludeCaseStr = YamlUtil.toYaml(listOf(mapOf("var2" to "1"))),
            totalCount = 10, // 3*3 + 2 - 1
            runningCount = 1,
            fastKill = true,
            maxConcurrency = 50
        )
        val result = mapOf(
            "os" to listOf("docker", "macos"),
            "var1" to listOf("a", "b", "c"),
            "var2" to listOf("1", "2", "3")
        )
//        Assert.assertEquals(JsonUtil.toJson(matrixControlOption.getAllContextCase()), JsonUtil.toJson(result))
    }

    @Test
    fun convertStrategyJson() {
        val matrixControlOption = MatrixControlOption(
            strategyStr = """{
                    "os": [docker,macos],
                    "var1": [a,b,c],
                    "var2": [1,2,3],
                }""",
            includeCaseStr = YamlUtil.toYaml(listOf(mapOf("var1" to "a"), mapOf("var2" to "2"))),
            excludeCaseStr = YamlUtil.toYaml(listOf(mapOf("var2" to "1"))),
            totalCount = 10, // 3*3 + 2 - 1
            runningCount = 1,
            fastKill = true,
            maxConcurrency = 50
        )
        val result =
            mapOf("os" to listOf("docker", "macos"), "var1" to listOf("a", "b", "c"), "var2" to listOf(1, 2, 3))

//        print(matrixControlOption.getAllContextCase())
//        Assert.assertEquals(JsonUtil.toJson(matrixControlOption.getAllContextCase()), JsonUtil.toJson(result))
    }

    @Test
    fun convertIncludeCase() {
        val matrixControlOption = MatrixControlOption(
            strategyStr = """{
                    "os": [docker,macos],
                    "var1": [a,b,c],
                    "var2": [1,2,3],
                }""",
            includeCaseStr = YamlUtil.toYaml(listOf(mapOf("var1" to "a"), mapOf("var2" to "2"))),
            excludeCaseStr = YamlUtil.toYaml(listOf(mapOf("var2" to "1"))),
            totalCount = 10, // 3*3 + 2 - 1
            runningCount = 1,
            fastKill = true,
            maxConcurrency = 50
        )
        val result = listOf(mapOf("var1" to "a"), mapOf("var2" to "2"))

//        print(matrixControlOption.convertIncludeCase())
//        Assert.assertEquals(JsonUtil.toJson(matrixControlOption.convertIncludeCase()), JsonUtil.toJson(result))
    }

    @Test
    fun convertExcludeCase() {
        val matrixControlOption = MatrixControlOption(
            strategyStr = """{
                    "os": [docker,macos],
                    "var1": [a,b,c],
                    "var2": [1,2,3],
                }""",
            includeCaseStr = YamlUtil.toYaml(listOf(mapOf("var1" to "a"), mapOf("var2" to "2"))),
            excludeCaseStr = YamlUtil.toYaml(listOf(mapOf("var2" to "1"))),
            totalCount = 10, // 3*3 + 2 - 1
            runningCount = 1,
            fastKill = true,
            maxConcurrency = 50
        )
        val result = listOf(mapOf("var2" to "1"))

//        print(matrixControlOption.convertExcludeCase())
//        Assert.assertEquals(JsonUtil.toJson(matrixControlOption.convertExcludeCase()), JsonUtil.toJson(result))
    }
}
