package org.foopgp.foomms

/** A call with the person of a conversation, shown among its messages: foomms+ only ([Calls.offered]). */
class Called(val kind: Kind, val seconds: Long) {
    enum class Kind { IN, OUT, MISSED, REFUSED }
}
