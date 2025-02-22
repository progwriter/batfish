package org.batfish.minesweeper.bdd;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.javabdd.BDD;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.NetworkFactory;
import org.batfish.datamodel.bgp.community.StandardCommunity;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.communities.ColonSeparatedRendering;
import org.batfish.datamodel.routing_policy.communities.CommunityExprsSet;
import org.batfish.datamodel.routing_policy.communities.CommunityMatchRegex;
import org.batfish.datamodel.routing_policy.communities.CommunitySet;
import org.batfish.datamodel.routing_policy.communities.CommunitySetDifference;
import org.batfish.datamodel.routing_policy.communities.CommunitySetExprReference;
import org.batfish.datamodel.routing_policy.communities.CommunitySetReference;
import org.batfish.datamodel.routing_policy.communities.CommunitySetUnion;
import org.batfish.datamodel.routing_policy.communities.InputCommunities;
import org.batfish.datamodel.routing_policy.communities.LiteralCommunitySet;
import org.batfish.datamodel.routing_policy.communities.StandardCommunityHighLowExprs;
import org.batfish.datamodel.routing_policy.expr.LiteralInt;
import org.batfish.minesweeper.CommunityVar;
import org.batfish.minesweeper.ConfigAtomicPredicates;
import org.batfish.minesweeper.ConfigAtomicPredicatesTestUtils;
import org.batfish.minesweeper.bdd.TransferBDD.Context;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Tests for {@link org.batfish.minesweeper.bdd.CommunitySetExprToBDD}. */
public class CommunitySetExprToBDDTest {
  private static final String HOSTNAME = "hostname";
  private static final String POLICY_NAME = "policy";
  private IBatfish _batfish;
  private Configuration _baseConfig;
  private ConfigAtomicPredicates _configAPs;
  private CommunitySetMatchExprToBDD.Arg _arg;
  private CommunitySetExprToBDD _communitySetExprToBDD;

  @Rule public ExpectedException _expectedException = ExpectedException.none();

  @Before
  public void setup() {
    NetworkFactory nf = new NetworkFactory();
    Configuration.Builder cb =
        nf.configurationBuilder()
            .setHostname(HOSTNAME)
            .setConfigurationFormat(ConfigurationFormat.CISCO_IOS);
    _baseConfig = cb.build();
    nf.vrfBuilder().setOwner(_baseConfig).setName(Configuration.DEFAULT_VRF_NAME).build();

    _batfish = new TransferBDDTest.MockBatfish(ImmutableSortedMap.of(HOSTNAME, _baseConfig));

    _configAPs =
        ConfigAtomicPredicatesTestUtils.forDevice(
            _batfish,
            _batfish.getSnapshot(),
            HOSTNAME,
            ImmutableSet.of(
                CommunityVar.from("^20:"),
                CommunityVar.from(":30$"),
                CommunityVar.from(StandardCommunity.parse("20:30")),
                CommunityVar.from(StandardCommunity.parse("21:30"))),
            null);
    TransferBDD transferBDD = new TransferBDD(_configAPs);
    RoutingPolicy testPolicy =
        nf.routingPolicyBuilder().setOwner(_baseConfig).setName(POLICY_NAME).build();
    BDDRoute bddRoute = new BDDRoute(transferBDD.getFactory(), _configAPs);
    _arg = new CommunitySetMatchExprToBDD.Arg(transferBDD, bddRoute, Context.forPolicy(testPolicy));

    _communitySetExprToBDD = new CommunitySetExprToBDD();
  }

  @Test
  public void testVisitCommunityExprsSet() {
    CommunityExprsSet ces =
        CommunityExprsSet.of(
            new StandardCommunityHighLowExprs(new LiteralInt(20), new LiteralInt(30)),
            new StandardCommunityHighLowExprs(new LiteralInt(21), new LiteralInt(30)));

    BDD result = _communitySetExprToBDD.visitCommunityExprsSet(ces, _arg);

    CommunityVar cvar1 = CommunityVar.from(StandardCommunity.parse("20:30"));
    CommunityVar cvar2 = CommunityVar.from(StandardCommunity.parse("21:30"));

    assertEquals(cvarToBDD(cvar1).or(cvarToBDD(cvar2)), result);
  }

  @Test
  public void testVisitCommunitySetDifference() {
    CommunitySetDifference csd =
        new CommunitySetDifference(
            new LiteralCommunitySet(
                CommunitySet.of(
                    StandardCommunity.parse("20:30"), StandardCommunity.parse("21:30"))),
            new CommunityMatchRegex(ColonSeparatedRendering.instance(), "^20:"));

    BDD result = _communitySetExprToBDD.visitCommunitySetDifference(csd, _arg);

    CommunityVar cvar1 = CommunityVar.from(StandardCommunity.parse("21:30"));
    CommunityVar cvar2 = CommunityVar.from("^20:");

    assertEquals(cvarToBDD(cvar1).diff(cvarToBDD(cvar2)), result);
  }

  @Test
  public void testVisitCommunitySetExprReference() {
    String name = "name";
    _baseConfig.setCommunitySetExprs(
        ImmutableMap.of(
            name,
            new LiteralCommunitySet(
                CommunitySet.of(
                    StandardCommunity.parse("20:30"), StandardCommunity.parse("21:30")))));
    CommunitySetExprReference cser = new CommunitySetExprReference(name);

    BDD result = _communitySetExprToBDD.visitCommunitySetExprReference(cser, _arg);

    CommunityVar cvar1 = CommunityVar.from(StandardCommunity.parse("20:30"));
    CommunityVar cvar2 = CommunityVar.from(StandardCommunity.parse("21:30"));

    assertEquals(cvarToBDD(cvar1).or(cvarToBDD(cvar2)), result);
  }

  @Test
  public void testVisitCommunitySetReference() {
    String name = "name";
    _baseConfig.setCommunitySets(
        ImmutableMap.of(
            name,
            CommunitySet.of(StandardCommunity.parse("20:30"), StandardCommunity.parse("21:30"))));
    CommunitySetReference csr = new CommunitySetReference(name);

    BDD result = _communitySetExprToBDD.visitCommunitySetReference(csr, _arg);

    CommunityVar cvar1 = CommunityVar.from(StandardCommunity.parse("20:30"));
    CommunityVar cvar2 = CommunityVar.from(StandardCommunity.parse("21:30"));

    assertEquals(cvarToBDD(cvar1).or(cvarToBDD(cvar2)), result);
  }

  @Test
  public void testVisitCommunitySetUnion() {
    CommunitySetUnion csu =
        CommunitySetUnion.of(
            new LiteralCommunitySet(CommunitySet.of(StandardCommunity.parse("20:30"))),
            new LiteralCommunitySet(CommunitySet.of(StandardCommunity.parse("21:30"))));

    BDD result = _communitySetExprToBDD.visitCommunitySetUnion(csu, _arg);

    CommunityVar cvar1 = CommunityVar.from(StandardCommunity.parse("20:30"));
    CommunityVar cvar2 = CommunityVar.from(StandardCommunity.parse("21:30"));

    assertEquals(cvarToBDD(cvar1).or(cvarToBDD(cvar2)), result);
  }

  @Test
  public void testVisitInputCommunities() {
    _expectedException.expect(UnsupportedOperationException.class);
    _communitySetExprToBDD.visitInputCommunities(InputCommunities.instance(), _arg);
  }

  private BDD cvarToBDD(CommunityVar cvar) {
    BDD[] aps = _arg.getBDDRoute().getCommunityAtomicPredicates();
    Map<CommunityVar, Set<Integer>> regexAtomicPredicates =
        _configAPs.getStandardCommunityAtomicPredicates().getRegexAtomicPredicates();
    return _arg.getTransferBDD()
        .getFactory()
        .orAll(
            regexAtomicPredicates.get(cvar).stream().map(i -> aps[i]).collect(Collectors.toList()));
  }
}
