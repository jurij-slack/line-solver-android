package org.linesolver.base.ui

import org.linesolver.base.model.Draft
import org.linesolver.base.material.catalogue.ProductCategory

/** Temporary presentation/application-entry restriction; never used by the mechanical solver. */
object DemoBungeePolicy {
    const val available = false
    const val status = "Currently unavailable"
    const val retainedMessage = "This rig contains bungees. Editing and calculations are currently unavailable in this demonstration. The configuration is retained unchanged. Load or create a rig without bungees."
    fun blocks(draft:Draft)=!available && !draft.bungees?.banks.isNullOrEmpty()
    fun allowsMaterialEdit(category:ProductCategory)=available || category!=ProductCategory.bungee
}
