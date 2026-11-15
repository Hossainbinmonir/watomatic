package com.parishod.watomatic.model.rules

enum class MatchType {
    EXACT,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    DOES_NOT_CONTAIN,
    ANY_KEYWORD,
    ALL_KEYWORDS,
    REGEX
}

enum class ContactFilterMode {
    ALL,
    INCLUDE_ONLY,
    EXCLUDE
}

enum class GroupFilterMode {
    BOTH,
    DIRECT_ONLY,
    GROUP_ONLY
}
