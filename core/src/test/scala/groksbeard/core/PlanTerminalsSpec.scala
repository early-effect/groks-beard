package groksbeard.core

import zio.test.*

object PlanTerminalsSpec extends ZIOSpecDefault:
  def spec =
    suite("PlanTerminals")(
      test("read-only commands are allowed") {
        assertTrue(
          PlanTerminals.allowed("ls", Nil),
          PlanTerminals.allowed("ls", List("-la")),
          PlanTerminals.allowed("echo", List("hi")),
          PlanTerminals.allowed("cat", List("Main.scala")),
          PlanTerminals.allowed("rg", List("PlanTerminals")),
          PlanTerminals.allowed("git", List("status")),
          PlanTerminals.allowed("git", List("--no-pager", "log", "-1")),
          PlanTerminals.allowed("sed", List("-n", "1p", "a")),
          PlanTerminals.allowed("cat", List("a")) && PlanTerminals.allowedScript("cat a | grep foo"),
        )
      },
      test("mutating commands are rejected") {
        assertTrue(
          !PlanTerminals.allowed("rm", List("-rf", "/tmp/beard-probe")),
          !PlanTerminals.allowed("echo", List("hi", ">", "mutated.txt")),
          !PlanTerminals.allowedScript("echo beard-probe > mutated.txt"),
          !PlanTerminals.allowedScript("cat a | tee mutated.txt"),
          !PlanTerminals.allowed("git", List("commit", "-am", "x")),
          !PlanTerminals.allowed("sed", List("-i", "s/a/b/", "Main.scala")),
          !PlanTerminals.allowed("npm", List("install")),
          !PlanTerminals.allowed("sbt", List("compile")),
        )
      },
      test("grok login-bash wrappers still classify the inner script") {
        assertTrue(
          PlanTerminals.allowed("/bin/bash", List("-lc", "ls")),
          !PlanTerminals.allowed("/bin/bash", List("-lc", "echo hi > mutated.txt")),
        )
      },
      test("compound scripts require every stage to be read-only") {
        assertTrue(
          PlanTerminals.allowedScript("ls; pwd"),
          !PlanTerminals.allowedScript("ls; rm -rf /tmp/beard-probe"),
          PlanTerminals.allowedScript("echo hi 2>/dev/null"),
        )
      },
    )
end PlanTerminalsSpec
