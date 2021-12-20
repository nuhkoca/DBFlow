package com.dbflow5.ksp.model

import com.dbflow5.ksp.ClassNames
import com.dbflow5.ksp.model.cache.ReferencesCache
import com.dbflow5.ksp.model.cache.TypeConverterCache
import com.dbflow5.ksp.model.properties.FieldProperties
import com.dbflow5.ksp.model.properties.ReferenceHolderProperties
import com.dbflow5.ksp.model.properties.isInferredTable
import com.dbflow5.ksp.model.properties.nameWithFallback
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName


sealed interface FieldModel {
    /**
     * The original name.
     */
    val name: NameModel

    /**
     * List of names, nested by call
     */
    val names: List<NameModel>

    /**
     * The declared type of the field.
     */
    val classType: TypeName
    val nonNullClassType: TypeName
        get() = classType.copy(nullable = false)

    /**
     * If type is inline.
     */
    val isInlineClass: Boolean

    /**
     * If true, must exist in constructor, otherwise will be ignored.
     */
    val isVal: Boolean

    val fieldType: FieldType
    val properties: FieldProperties?

    /**
     * This can be View, Normal, or Query. Based on [ClassModel]
     */
    val enclosingClassType: TypeName

    /**
     *  Join by name for properties.
     */
    val propertyName
        get() = names.joinToString("_") { it.shortName }

    /**
     * [useLastNull] Last name if we want ? inserted
     */
    fun accessName(useLastNull: Boolean = false) = names
        .withIndex()
        .joinToString(".") { (index, value) ->
            if (index < names.size - 1 || useLastNull) {
                value.accessName
            } else {
                value.shortName
            }
        }

    val dbName
        get() = properties.nameWithFallback(propertyName)

    sealed interface FieldType {
        object Normal : FieldType
        data class PrimaryAuto(
            val isAutoIncrement: Boolean,
            val isRowId: Boolean,
            val quickCheckPrimaryKey: Boolean,
        ) : FieldType
    }

}

fun FieldModel.hasTypeConverter(typeConverterCache: TypeConverterCache) =
    properties?.let { properties ->
        properties.typeConverterClassName as TypeName != ClassNames.TypeConverter
            || typeConverterCache.has(classType)
    }
        ?: typeConverterCache.has(classType)

fun FieldModel.typeConverter(typeConverterCache: TypeConverterCache) =
    typeConverterCache[classType, properties?.typeConverterClassName?.toString()
        ?: ""]

/**
 * Description:
 */
data class SingleFieldModel(
    override val name: NameModel,

    /**
     * The declared type of the field.
     */
    override val classType: TypeName,
    override val fieldType: FieldModel.FieldType,
    override val properties: FieldProperties?,
    override val enclosingClassType: TypeName,
    override val names: List<NameModel> = listOf(name),
    override val isInlineClass: Boolean,
    override val isVal: Boolean,
) : ObjectModel, FieldModel

data class ReferenceHolderModel(
    override val name: NameModel,
    override val classType: TypeName,
    override val fieldType: FieldModel.FieldType,
    override val properties: FieldProperties?,
    val referenceHolderProperties: ReferenceHolderProperties,
    override val enclosingClassType: TypeName,
    override val names: List<NameModel> = listOf(name),
    val type: Type,
    override val isInlineClass: Boolean,
    val inputType: KSType,
    override val isVal: Boolean,
) : ObjectModel, FieldModel {

    enum class Type {
        ForeignKey,

        /**
         * These are either ColumnMap or inline classes.
         */
        Computed
    }

    fun references(
        referencesCache: ReferencesCache,
        nameToNest: NameModel? = null,
    ): List<SingleFieldModel> {
        when (type) {
            Type.ForeignKey -> {
                // treat field of not table type as a single model type.
                if (!referencesCache.isTable(this)) {
                    return listOf(toSingleModel())
                }
                val tableTypeName = if (referenceHolderProperties.isInferredTable()) {
                    referenceHolderProperties.referencedTableTypeName
                } else {
                    classType
                }
                return when (referenceHolderProperties.referencesType) {
                    is ReferenceHolderProperties.ReferencesType.All -> referencesCache.resolveExistingFields(
                        tableTypeName
                    )
                    is ReferenceHolderProperties.ReferencesType.Specific -> {
                        referencesCache.resolveReferencesOnExisting(
                            referenceHolderProperties.referencesType.references,
                            tableTypeName,
                        )
                    }
                }.map { reference ->
                    if (nameToNest != null) {
                        reference.copy(
                            names = reference.names.toMutableList().apply {
                                add(0, nameToNest)
                            }
                        )
                    } else reference
                }
            }
            Type.Computed -> {
                return when (referenceHolderProperties.referencesType) {
                    is ReferenceHolderProperties.ReferencesType.All -> referencesCache.resolveComputedFields(
                        inputType,
                    )
                    is ReferenceHolderProperties.ReferencesType.Specific -> {
                        referencesCache.resolveReferencesOnComputedFields(
                            referenceHolderProperties.referencesType.references,
                            inputType,
                        )
                    }
                }
            }
        }
    }
}

fun ReferenceHolderModel.toSingleModel() =
    SingleFieldModel(
        name,
        classType,
        fieldType,
        properties,
        enclosingClassType,
        names,
        isInlineClass,
        isVal
    )


