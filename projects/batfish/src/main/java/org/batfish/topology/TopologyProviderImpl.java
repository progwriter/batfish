package org.batfish.topology;

import static org.batfish.common.topology.TopologyUtil.computeLayer2Topology;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.common.BatfishException;
import org.batfish.common.NetworkSnapshot;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.topology.GlobalBroadcastNoPointToPoint;
import org.batfish.common.topology.HybridL3Adjacencies;
import org.batfish.common.topology.IpOwners;
import org.batfish.common.topology.L3Adjacencies;
import org.batfish.common.topology.Layer1Topology;
import org.batfish.common.topology.TopologyProvider;
import org.batfish.common.topology.TopologyUtil;
import org.batfish.common.topology.TunnelTopology;
import org.batfish.common.topology.broadcast.BroadcastL3Adjacencies;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.NetworkConfigurations;
import org.batfish.datamodel.Topology;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.ipsec.IpsecTopology;
import org.batfish.datamodel.ospf.OspfTopology;
import org.batfish.datamodel.ospf.OspfTopologyUtils;
import org.batfish.datamodel.vxlan.VxlanTopology;
import org.batfish.datamodel.vxlan.VxlanTopologyUtils;
import org.batfish.storage.StorageProvider;

@ParametersAreNonnullByDefault
public final class TopologyProviderImpl implements TopologyProvider {
  /** Create a new topology provider for a given instance of {@link IBatfish} */
  public TopologyProviderImpl(IBatfish batfish, StorageProvider storage) {
    _batfish = batfish;
    _storage = storage;
  }

  @Override
  public IpOwners getIpOwners(NetworkSnapshot snapshot) {
    return _ipOwners.getUnchecked(snapshot);
  }

  @Override
  public Optional<Layer1Topology> getLayer1LogicalTopology(NetworkSnapshot networkSnapshot) {
    return _layer1LogicalTopologies.getUnchecked(networkSnapshot);
  }

  @Override
  public Optional<Layer1Topology> getLayer1PhysicalTopology(NetworkSnapshot networkSnapshot) {
    return _layer1PhysicalTopologies.getUnchecked(networkSnapshot);
  }

  @Override
  @Nonnull
  public IpsecTopology getInitialIpsecTopology(NetworkSnapshot networkSnapshot) {
    return _initialIpsecTopologies.getUnchecked(networkSnapshot);
  }

  @Override
  public Topology getInitialLayer3Topology(NetworkSnapshot networkSnapshot) {
    return _initialLayer3Topologies.getUnchecked(networkSnapshot);
  }

  @Override
  public @Nonnull L3Adjacencies getInitialL3Adjacencies(NetworkSnapshot networkSnapshot) {
    return _initialL3Adjacencies.getUnchecked(networkSnapshot);
  }

  @Override
  public OspfTopology getInitialOspfTopology(NetworkSnapshot networkSnapshot) {
    return _initialOspfTopologies.getUnchecked(networkSnapshot);
  }

  @Override
  public OspfTopology getOspfTopology(NetworkSnapshot networkSnapshot) {
    return computeOspfTopology(networkSnapshot);
  }

  @Override
  public Optional<Layer1Topology> getRawLayer1PhysicalTopology(NetworkSnapshot networkSnapshot) {
    return _rawLayer1PhysicalTopologies.getUnchecked(networkSnapshot);
  }

  @Override
  public Optional<Layer1Topology> getSynthesizedLayer1Topology(NetworkSnapshot networkSnapshot) {
    try {
      return _storage
          .loadSynthesizedLayer1Topology(networkSnapshot)
          .map(
              l1 ->
                  TopologyUtil.cleanLayer1PhysicalTopology(
                      l1, _batfish.loadConfigurations(networkSnapshot)));
    } catch (IOException e) {
      throw new BatfishException("Could not load BGP topology", e);
    }
  }

  @Override
  public Topology getRawLayer3Topology(NetworkSnapshot networkSnapshot) {
    return _rawLayer3Topologies.getUnchecked(networkSnapshot);
  }

  @Override
  public VxlanTopology getInitialVxlanTopology(NetworkSnapshot snapshot) {
    return _initialVxlanTopologies.getUnchecked(snapshot);
  }

  @Override
  public @Nonnull BgpTopology getBgpTopology(NetworkSnapshot snapshot) {
    try {
      return _storage.loadBgpTopology(snapshot);
    } catch (IOException e) {
      throw new BatfishException("Could not load BGP topology", e);
    }
  }

  @Override
  public @Nonnull Topology getLayer3Topology(NetworkSnapshot snapshot) {
    try {
      return _storage.loadLayer3Topology(snapshot);
    } catch (IOException e) {
      throw new BatfishException("Could not load layer-3 topology", e);
    }
  }

  @Override
  public @Nonnull L3Adjacencies getL3Adjacencies(@Nonnull NetworkSnapshot snapshot) {
    try {
      return _storage.loadL3Adjacencies(snapshot);
    } catch (IOException e) {
      throw new BatfishException("Could not load L3 Adjacencies", e);
    }
  }

  @Override
  public @Nonnull VxlanTopology getVxlanTopology(NetworkSnapshot snapshot) {
    try {
      return _storage.loadVxlanTopology(snapshot);
    } catch (IOException e) {
      throw new BatfishException("Could not load VXLAN topology", e);
    }
  }

  @Nonnull
  @Override
  public TunnelTopology getInitialTunnelTopology(NetworkSnapshot snapshot) {
    return _initialTunnelTopologies.getUnchecked(snapshot);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  // PRIVATE IMPLEMENTATION
  ///////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * The maximum number of different snapshots for which any specific topology will be cached. Note
   * that the set of snapshots for any given topology will not be the same.
   */
  private static final int MAX_CACHED_SNAPSHOTS = 3;

  private final IBatfish _batfish;
  private final StorageProvider _storage;

  // NOTE: only the "raw" or "initial" versions of topologies are cached. This choice was made to
  // ease developer iteration on BDP: if the dataplane is re-generated (presumably, via a call to
  // generate_dataplane), the backend will not cache dataplane-derived topologies.

  private final LoadingCache<NetworkSnapshot, IpOwners> _ipOwners =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeIpOwners));

  private final LoadingCache<NetworkSnapshot, Optional<Layer1Topology>> _layer1LogicalTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeLayer1LogicalTopology));
  private final LoadingCache<NetworkSnapshot, Optional<Layer1Topology>>
      _rawLayer1PhysicalTopologies =
          CacheBuilder.newBuilder()
              .maximumSize(MAX_CACHED_SNAPSHOTS)
              .build(CacheLoader.from(this::computeRawLayer1PhysicalTopology));
  private final LoadingCache<NetworkSnapshot, Optional<Layer1Topology>> _layer1PhysicalTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeLayer1PhysicalTopology));

  private final LoadingCache<NetworkSnapshot, L3Adjacencies> _initialL3Adjacencies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .softValues()
          .build(CacheLoader.from(this::computeInitialL3Adjacencies));

  private final LoadingCache<NetworkSnapshot, Topology> _rawLayer3Topologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeRawLayer3Topology));

  private final LoadingCache<NetworkSnapshot, Topology> _initialLayer3Topologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeInitialLayer3Topology));

  private final LoadingCache<NetworkSnapshot, OspfTopology> _initialOspfTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeInitialOspfTopology));

  private final LoadingCache<NetworkSnapshot, IpsecTopology> _initialIpsecTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeInitialIpsecTopology));
  private final LoadingCache<NetworkSnapshot, TunnelTopology> _initialTunnelTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeInitialTunnelTopology));

  private final LoadingCache<NetworkSnapshot, VxlanTopology> _initialVxlanTopologies =
      CacheBuilder.newBuilder()
          .maximumSize(MAX_CACHED_SNAPSHOTS)
          .build(CacheLoader.from(this::computeVxlanTopology));

  private @Nonnull IpOwners computeIpOwners(NetworkSnapshot snapshot) {
    Span span = GlobalTracer.get().buildSpan("TopologyProviderImpl::computeIpOwners").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return new IpOwners(_batfish.loadConfigurations(snapshot));
    } finally {
      span.finish();
    }
  }

  private @Nonnull Optional<Layer1Topology> computeLayer1LogicalTopology(
      NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeLayer1LogicalTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return getLayer1PhysicalTopology(networkSnapshot)
          .map(
              layer1PhysicalTopology ->
                  TopologyUtil.computeLayer1LogicalTopology(
                      layer1PhysicalTopology, _batfish.loadConfigurations(networkSnapshot)));
    } finally {
      span.finish();
    }
  }

  @Nonnull
  private Optional<Layer1Topology> computeLayer1PhysicalTopology(NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeLayer1PhysicalTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      Optional<Layer1Topology> rawTopology =
          getRawLayer1PhysicalTopology(networkSnapshot)
              .map(
                  rawLayer1PhysicalTopology ->
                      TopologyUtil.cleanLayer1PhysicalTopology(
                          rawLayer1PhysicalTopology, _batfish.loadConfigurations(networkSnapshot)));
      Optional<Layer1Topology> synthesizedTopology = getSynthesizedLayer1Topology(networkSnapshot);
      return TopologyUtil.unionLayer1PhysicalTopologies(rawTopology, synthesizedTopology);
    } finally {
      span.finish();
    }
  }

  /** Computes {@link IpsecTopology} with edges that have compatible IPsec settings */
  private IpsecTopology computeInitialIpsecTopology(NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeInitialIpsecTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return TopologyUtil.computeIpsecTopology(_batfish.loadConfigurations(networkSnapshot));
    } finally {
      span.finish();
    }
  }

  private Topology computeInitialLayer3Topology(NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeInitialLayer3Topology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return TopologyUtil.computeLayer3Topology(
          getRawLayer3Topology(networkSnapshot), ImmutableSet.of());
    } finally {
      span.finish();
    }
  }

  private @Nonnull L3Adjacencies computeInitialL3Adjacencies(NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeInitialL3Adjacencies").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      if (L3Adjacencies.USE_NEW_METHOD) {
        return BroadcastL3Adjacencies.create(
            getLayer1LogicalTopology(networkSnapshot).orElse(Layer1Topology.EMPTY),
            VxlanTopology.EMPTY,
            _batfish.loadConfigurations(networkSnapshot));
      }
      return getLayer1LogicalTopology(networkSnapshot)
          .<L3Adjacencies>map(
              l1 -> {
                Map<String, Configuration> configs = _batfish.loadConfigurations(networkSnapshot);
                return HybridL3Adjacencies.create(
                    getRawLayer1PhysicalTopology(networkSnapshot).orElse(Layer1Topology.EMPTY),
                    l1,
                    computeLayer2Topology(l1, VxlanTopology.EMPTY, configs),
                    configs);
              })
          .orElse(GlobalBroadcastNoPointToPoint.instance());
    } finally {
      span.finish();
    }
  }

  private @Nonnull Optional<Layer1Topology> computeRawLayer1PhysicalTopology(
      NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get()
            .buildSpan("TopologyProviderImpl::computeRawLayer1PhysicalTopology")
            .start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return Optional.ofNullable(
          _storage.loadLayer1Topology(networkSnapshot.getNetwork(), networkSnapshot.getSnapshot()));
    } finally {
      span.finish();
    }
  }

  private @Nonnull Topology computeRawLayer3Topology(NetworkSnapshot networkSnapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeRawLayer3Topology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      Map<String, Configuration> configurations = _batfish.loadConfigurations(networkSnapshot);
      L3Adjacencies adjacencies = getInitialL3Adjacencies(networkSnapshot);
      return TopologyUtil.computeRawLayer3Topology(adjacencies, configurations);
    } finally {
      span.finish();
    }
  }

  private @Nonnull OspfTopology computeInitialOspfTopology(NetworkSnapshot snapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeInitialOspfTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return OspfTopologyUtils.computeOspfTopology(
          NetworkConfigurations.of(_batfish.loadConfigurations(snapshot)),
          getInitialLayer3Topology(snapshot));
    } finally {
      span.finish();
    }
  }

  private @Nonnull OspfTopology computeOspfTopology(NetworkSnapshot snapshot) {
    Span span = GlobalTracer.get().buildSpan("TopologyProviderImpl::computeOspfTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return OspfTopologyUtils.computeOspfTopology(
          NetworkConfigurations.of(_batfish.loadConfigurations(snapshot)),
          getLayer3Topology(snapshot));
    }
  }

  private @Nonnull TunnelTopology computeInitialTunnelTopology(NetworkSnapshot snapshot) {
    Span span =
        GlobalTracer.get().buildSpan("TopologyProviderImpl::computeInitialTunnelTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return TopologyUtil.computeInitialTunnelTopology(_batfish.loadConfigurations(snapshot));
    } finally {
      span.finish();
    }
  }

  private @Nonnull VxlanTopology computeVxlanTopology(NetworkSnapshot snapshot) {
    Span span = GlobalTracer.get().buildSpan("TopologyProviderImpl::computeVxlanTopology").start();
    try (Scope scope = GlobalTracer.get().scopeManager().activate(span)) {
      assert scope != null; // avoid unused warning
      return VxlanTopologyUtils.computeVxlanTopology(_batfish.loadConfigurations(snapshot));
    } finally {
      span.finish();
    }
  }
}
