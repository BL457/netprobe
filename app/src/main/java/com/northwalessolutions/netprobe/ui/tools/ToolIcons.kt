package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.northwalessolutions.netprobe.engine.tools.ToolIcon

/** The Material vector of each icon in the fixed set; the names are [ToolIconMap]'s. */
object ToolIcons {
    fun vector(icon: ToolIcon): ImageVector = when (icon) {
        ToolIcon.WEB -> Icons.Filled.Language
        ToolIcon.SEARCH -> Icons.Filled.Search
        ToolIcon.LOCK -> Icons.Filled.Lock
        ToolIcon.FOLDER -> Icons.Filled.FolderOpen
        ToolIcon.SHARE -> Icons.Filled.Share
        ToolIcon.DNS -> Icons.Filled.Dns
        ToolIcon.TERMINAL -> Icons.Filled.Terminal
        ToolIcon.FINGERPRINT -> Icons.Filled.Fingerprint
        ToolIcon.NETWORK -> Icons.Filled.Lan
        ToolIcon.WIFI -> Icons.Filled.Wifi
        ToolIcon.INFO -> Icons.Filled.Info
        ToolIcon.BOLT -> Icons.Filled.Bolt
    }
}
