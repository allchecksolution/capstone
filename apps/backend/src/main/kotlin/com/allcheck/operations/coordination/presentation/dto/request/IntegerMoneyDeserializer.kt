package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/** 금액의 실수/문자열 자동 변환으로 원 단위 금액이 조용히 달라지는 것을 막는다. */
class IntegerMoneyDeserializer : ValueDeserializer<Long>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Long {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        return parser.longValue
    }
}
