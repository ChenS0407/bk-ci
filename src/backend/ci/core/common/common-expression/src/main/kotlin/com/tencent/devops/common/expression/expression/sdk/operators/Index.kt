package com.tencent.devops.common.expression.expression.sdk.operators

import com.tencent.devops.common.expression.expression.EvaluationResult
import com.tencent.devops.common.expression.expression.sdk.Container
import com.tencent.devops.common.expression.expression.sdk.EvaluationContext
import com.tencent.devops.common.expression.expression.sdk.ExpressionNode
import com.tencent.devops.common.expression.expression.sdk.ExpressionUtility
import com.tencent.devops.common.expression.expression.sdk.IReadOnlyArray
import com.tencent.devops.common.expression.expression.sdk.IReadOnlyObject
import com.tencent.devops.common.expression.expression.sdk.Literal
import com.tencent.devops.common.expression.expression.sdk.MemoryCounter
import com.tencent.devops.common.expression.expression.sdk.ResultMemory
import com.tencent.devops.common.expression.expression.sdk.Wildcard
import kotlin.math.floor

@Suppress("NestedBlockDepth")
class Index : Container() {

    override val traceFullyRealized = true

    override fun convertToExpression(): String {
        // 验证我们是否可以简化表达式，我们宁愿返回
        // github.sha 然后 github['sha'] 所以我们检查这是否是一个简单的案例。
        return if (parameters[1] is Literal &&
            (parameters[1] as Literal).value is String &&
            ExpressionUtility.isLegalKeyword((parameters[1] as Literal).value as String)
        ) {
            "${parameters[0].convertToExpression()}.${(parameters[1] as Literal).value as String}"
        } else {
            "${parameters[0].convertToExpression()}[${parameters[1].convertToExpression()}]"
        }
    }

    override fun convertToRealizedExpression(context: EvaluationContext): String {
        // Check if the result was stored
        val (re, result) = context.tryGetTraceResult(this)
        if (result) {
            return re!!
        }

        return parameters[0].convertToRealizedExpression(context) +
            "[${parameters[1].convertToRealizedExpression(context)}]"
    }

    override fun evaluateCore(context: EvaluationContext): Pair<ResultMemory?, Any?> {
        val left = parameters[0].evaluate(context)

        // Not a collection
        val (collection, ok) = left.tryGetCollectionInterface()
        if (!ok) {
            return Pair(
                null,
                if (parameters[1] is Wildcard) {
                    FilteredArray()
                } else {
                    null
                }
            )
        }
        // Filtered array
        else if (collection is FilteredArray) {
            val (mem, obj) = handleFilteredArray(context, collection)
            return Pair(mem, obj)
        }
        // Object
        else if (collection is IReadOnlyObject) {
            val (mem, obj) = handleObject(context, collection)
            return Pair(mem, obj)
        }
        // Array
        else if (collection is IReadOnlyArray<*>) {
            val (mem, obj) = handleArray(context, collection)
            return Pair(mem, obj)
        }

        return Pair(null, null)
    }

    private fun handleFilteredArray(
        context: EvaluationContext,
        filteredArray: FilteredArray
    ): Pair<ResultMemory, Any?> {
        val result = FilteredArray()
        val counter = MemoryCounter(this, context.options.maxMemory)

        val index = IndexHelper(context, parameters[1])

        filteredArray.forEach { item ->
            // Leverage the expression SDK to traverse the object
            val itemResult = EvaluationResult.createIntermediateResult(context, item)
            val (nestedCollection, ok) = itemResult.tryGetCollectionInterface()
            if (ok) {
                // Apply the index to each child object
                if (nestedCollection is IReadOnlyObject) {
                    // Wildcard
                    if (index.isWildcard) {
                        nestedCollection.values.forEach { value ->
                            result.add(value)
                            counter.add(Int.SIZE_BYTES)
                        }
                    }
                    // String
                    else if (index.hasStringIndex) {
                        val (nestedObjectValue, res) = nestedCollection.tryGetValue(index.stringIndex!!)
                        if (res) {
                            result.add(nestedObjectValue)
                            counter.add(Int.SIZE_BYTES)
                        }
                    }
                }
                // Apply the index to each child array
                else if (nestedCollection is IReadOnlyArray<*>) {
                    // Wildcard
                    if (index.isWildcard) {
                        nestedCollection.forEach { value ->
                            result.add(value)
                            counter.add(Int.SIZE_BYTES)
                        }
                    }
                    // Int
                    else if (index.hasIntegerIndex && index.integerIndex < nestedCollection.count) {
                        result.add(nestedCollection[index.integerIndex])
                        counter.add(Int.SIZE_BYTES)
                    }
                }
            }
        }
        return Pair(ResultMemory().also { it.bytes = counter.currentBytes }, result)
    }

    private fun handleObject(
        context: EvaluationContext,
        obj: IReadOnlyObject
    ): Pair<ResultMemory?, Any?> {
        val index = IndexHelper(context, parameters[1])

        // Wildcard
        if (index.isWildcard) {
            val filteredArray = FilteredArray()
            val counter = MemoryCounter(this, context.options.maxMemory)
            counter.addMinObjectSize()

            obj.values.forEach { value ->
                filteredArray.add(value)
                counter.add(Int.SIZE_BYTES)
            }

            return Pair(ResultMemory().also { it.bytes = counter.currentBytes }, filteredArray)
        }
        // String
        else {
            val (result, ok) = obj.tryGetValue(index.stringIndex!!)
            if (index.hasStringIndex && ok) {
                return Pair(null, result)
            }
        }

        return Pair(null, null)
    }

    private fun handleArray(
        context: EvaluationContext,
        array: IReadOnlyArray<*>
    ): Pair<ResultMemory?, Any?> {
        val index = IndexHelper(context, parameters[1])

        // Wildcard
        if (index.isWildcard) {
            val filtered = FilteredArray()
            val counter = MemoryCounter(this, context.options.maxMemory)
            counter.addMinObjectSize()

            array.forEach { item ->
                filtered.add(item)
                counter.add(Int.SIZE_BYTES)
            }

            return Pair(ResultMemory().also { it.bytes = counter.currentBytes }, filtered)
        }
        // Integer
        else if (index.hasIntegerIndex && index.integerIndex < array.count) {
            return Pair(null, array[index.integerIndex])
        }

        return Pair(null, null)
    }

    private class FilteredArray(
        private val mList: MutableList<Any?> = mutableListOf()
    ) : IReadOnlyArray<Any?> {

        fun add(o: Any?) {
            mList.add(o)
        }

        override val count: Int
            get() = mList.count()

        override operator fun get(index: Int): Any? {
            return mList[index]
        }

        override fun iterator(): Iterator<Any?> {
            return mList.iterator()
        }
    }

    private class IndexHelper(context: EvaluationContext, val parameter: ExpressionNode) {
        private val mResult: EvaluationResult
        private val mIntegerIndex: Lazy<Int?>
        private val mStringIndex: Lazy<String?>

        init {
            mResult = parameter.evaluate(context)

            mIntegerIndex = lazy {
                var doubleIndex = mResult.convertToNumber()
                if (doubleIndex.isNaN() || doubleIndex < 0.0) {
                    return@lazy null
                }

                doubleIndex = floor(doubleIndex)
                if (doubleIndex > Int.MAX_VALUE.toDouble()) {
                    return@lazy null
                }

                return@lazy doubleIndex.toInt()
            }

            mStringIndex = lazy {
                if (mResult.isPrimitive) {
                    mResult.convertToString()
                } else {
                    null
                }
            }
        }

        val hasIntegerIndex get() = mIntegerIndex.value != null

        val hasStringIndex get() = mStringIndex.value != null

        val isWildcard get() = parameter is Wildcard

        val integerIndex get() = mIntegerIndex.value ?: 0

        val stringIndex get() = mStringIndex.value
    }
}
