package io.github.fncontroloption.zig

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project

class ZigNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean {
        return name in KEYWORDS
    }

    override fun isIdentifier(name: String, project: Project?): Boolean {
        return true
    }
}
