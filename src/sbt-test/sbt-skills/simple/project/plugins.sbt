{
  val pluginVersion = System.getProperty("plugin.version")
  if (pluginVersion == null)
    throw new RuntimeException(
      """|The system property 'plugin.version' is not defined.
                                  |Specify it in scriptedLaunchOpts as -Dplugin.version=<version>.""".stripMargin
    )
  else addSbtPlugin("com.github.aborg0" % """sbt-skills""" % pluginVersion)
}
