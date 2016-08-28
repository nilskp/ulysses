package ulysses.conv

trait JavaEnumConverter[T <: java.lang.Enum[T]] extends TypeConverter[T] {
  protected val byName =
    jvmType.getEnumConstants.foldLeft(Map.empty[String, T]) {
      case (map, value) =>
        val t = value.asInstanceOf[T]
        map.updated(t.name, t)
    }
  override def writeAs(value: T) = value.name
}
