package com.allcheck.operations.coordination.infrastructure.dispatch

internal object AdministrativeArea {
    private val whitespace = Regex("\\s+")
    private val aliases = mapOf(
        "서울특별시" to "서울",
        "부산광역시" to "부산",
        "대구광역시" to "대구",
        "인천광역시" to "인천",
        "광주광역시" to "광주",
        "대전광역시" to "대전",
        "울산광역시" to "울산",
        "세종특별자치시" to "세종",
        "경기도" to "경기",
        "강원도" to "강원",
        "강원특별자치도" to "강원",
        "충청북도" to "충북",
        "충청남도" to "충남",
        "전라북도" to "전북",
        "전북특별자치도" to "전북",
        "전라남도" to "전남",
        "경상북도" to "경북",
        "경상남도" to "경남",
        "제주도" to "제주",
        "제주특별자치도" to "제주",
        "서울시" to "서울",
    )

    fun serves(selectedRegion: String, siteArea: String): Boolean {
        val selected = parts(selectedRegion)
        val site = parts(siteArea)
        return selected.isNotEmpty() && site.take(selected.size) == selected
    }

    private fun parts(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(whitespace)
        return listOf(aliases[parts.first()] ?: parts.first()) + parts.drop(1)
    }
}
