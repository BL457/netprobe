package com.northwalessolutions.netprobe.wordlists

import java.io.InputStream

/** A bundled list: its id, name and a fresh InputStream over its raw resource (spec 5.6.1). */
class BuiltInList(val id: String, val name: String, val open: () -> InputStream)
