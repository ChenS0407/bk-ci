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

package com.tencent.devops.common.api.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GrammarAnalysisTest {

    private val aesKey = "123A4$5@678"

    @Test
    fun analysis1() {
        val str = "a == a"
        val items = Lex(str.toList().toMutableList()).getToken()
        try {
            assertEquals(true, GrammarAnalysis(items).analysis())
        } catch (e: Exception) {
            fail()
        }
    }

    @Test
    fun analysis2() {
        val str = " a==a "
        val items = Lex(str.toList().toMutableList()).getToken()
        try {
            assertEquals(true, GrammarAnalysis(items).analysis())
        } catch (e: Exception) {
            fail()
        }
    }

    @Test
    fun analysis3() {
        val str = "'push'== 'push' && (true && (bbb != aaa && 1 <= 2 )) && (( !true == false) || (!false != false ))"
        val items = Lex(str.toList().toMutableList()).getToken()
        try {
            assertEquals(true, GrammarAnalysis(items).analysis())
        } catch (e: Exception) {
            fail()
        }
    }

    @Test
    fun analysis4() {
        val str = "'push'== 'push' && (true && (bbb != aaa && 1 <= 2 )) && (( !true == false) || (!false != false ) "
        val items = Lex(str.toList().toMutableList()).getToken()
        try {
            GrammarAnalysis(items).analysis()
        } catch (e: Exception) {
            assertEquals(e.javaClass, IllegalArgumentException::class.java)
        }
    }

    @Test
    fun analysis5() {
        val str = "featch/reviews == featch/reviews"
        val items = Lex(str.toList().toMutableList()).getToken()
        try {
            assertEquals(true, GrammarAnalysis(items).analysis())
        } catch (e: Exception) {
            fail()
        }
    }
}
