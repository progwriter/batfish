package org.batfish.grammar.cisco_xr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.batfish.common.util.Resources.readResource;
import static org.batfish.datamodel.matchers.MapMatchers.hasKeys;
import static org.batfish.main.BatfishTestUtils.DUMMY_SNAPSHOT_1;
import static org.batfish.main.BatfishTestUtils.configureBatfishTestSettings;
import static org.hamcrest.MatcherAssert.assertThat;

import javax.annotation.Nonnull;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.commons.lang3.SerializationUtils;
import org.batfish.common.BatfishLogger;
import org.batfish.common.Warnings;
import org.batfish.config.Settings;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.grammar.silent_syntax.SilentSyntaxCollection;
import org.batfish.main.Batfish;
import org.batfish.representation.cisco_xr.CiscoXrConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Tests for https://github.com/batfish/batfish/issues/6005. */
public final class GitHub6005Test {

  private static final String TESTCONFIGS_PREFIX = "org/batfish/grammar/cisco_xr/testconfigs/";

  @Rule public ExpectedException _thrown = ExpectedException.none();

  private @Nonnull CiscoXrConfiguration parseVendorConfig(String hostname) {
    String src = readResource(TESTCONFIGS_PREFIX + hostname, UTF_8);
    Settings settings = new Settings();
    configureBatfishTestSettings(settings);
    CiscoXrCombinedParser ciscoXrParser = new CiscoXrCombinedParser(src, settings);
    CiscoXrControlPlaneExtractor extractor =
        new CiscoXrControlPlaneExtractor(
            src,
            ciscoXrParser,
            ConfigurationFormat.CISCO_IOS_XR,
            new Warnings(),
            new SilentSyntaxCollection());
    ParserRuleContext tree =
        Batfish.parse(
            ciscoXrParser, new BatfishLogger(BatfishLogger.LEVELSTR_FATAL, false), settings);
    extractor.processParseTree(DUMMY_SNAPSHOT_1, tree);
    CiscoXrConfiguration vendorConfiguration =
        (CiscoXrConfiguration) extractor.getVendorConfiguration();
    vendorConfiguration.setFilename(TESTCONFIGS_PREFIX + hostname);
    // crash if not serializable
    return SerializationUtils.clone(vendorConfiguration);
  }

  @Test
  public void testGitHub6005() {
    CiscoXrConfiguration c = parseVendorConfig("gh6005");
    assertThat(c.getAsPathSets(), hasKeys("FOO"));
  }
}
