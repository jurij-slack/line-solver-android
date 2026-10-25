package org.linesolver.base

import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.material.catalogue.CatalogueCodec
import org.linesolver.base.material.catalogue.WebbingCatalogueImport
import java.io.File

/** Explicit generation command only. Ordinary tests never refresh expected outputs. */
object CatalogueImportMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2 && args[1] in listOf("--write", "--check"))
        val root = File(args[0]); val out = File(root, "catalogue")
        val legacy = JsonMaterialRepository(File(root, "data/materials/webbing_material_models_v1.json").readText())
        val result = WebbingCatalogueImport(legacy, File(out, "reconciliation-v1.json").readText())
            .import(File(root, "data/materials/webbings.json").readText())
        val artifacts = mapOf("webbing-catalogue-v1.json" to CatalogueCodec.encode(result.catalogue) + "\n",
            "inventory-v1.json" to result.inventoryText() + "\n", "RECONCILIATION.md" to result.report())
        artifacts.forEach { (name, text) ->
            val file = File(out, name)
            if (args[1] == "--write") file.writeText(text)
            else check(file.readText() == text) { "Generated artifact differs: $file" }
        }
        println(result.inventory["counts"])
        println(if (args[1] == "--write") "Wrote catalogue, inventory and reconciliation report." else "All catalogue artifacts reproduce exactly.")
    }
}
