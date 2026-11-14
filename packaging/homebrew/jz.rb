class Jz < Formula
  desc "Command-line client for the Janzeer blockchain"
  homepage "https://janzeer.org"
  url "https://github.com/janzeerOrg/janzeer-cli/releases/download/v0.1.1/jz-0.1.1.jar"
  sha256 "b4499083a0d244fb10c78d81e853a1ebdcaa0182b82ab467fc41c9b8fa8e87d9"
  license "Apache-2.0"

  depends_on "openjdk@17"

  def install
    libexec.install "jz-#{version}.jar"
    bin.write_jar_script libexec/"jz-#{version}.jar", "jz"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/jz version")
  end
end
