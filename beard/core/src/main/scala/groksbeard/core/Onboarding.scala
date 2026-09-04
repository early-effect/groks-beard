package groksbeard.core

object Onboarding:
  val CliInstallHint =
    "Install the Grok Build CLI: curl -fsSL https://x.ai/cli/install.sh | bash && grok login"

  def missingCliMessage(searched: List[String]): String =
    s"Grok CLI not found. $CliInstallHint\nLooked in: ${searched.mkString(", ")}"
end Onboarding
