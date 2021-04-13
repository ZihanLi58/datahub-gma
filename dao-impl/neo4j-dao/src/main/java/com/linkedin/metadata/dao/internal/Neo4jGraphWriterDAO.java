package com.linkedin.metadata.dao.internal;

import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.dao.exception.RetryLimitReached;
import com.linkedin.metadata.dao.utils.Statement;
import com.linkedin.metadata.validator.EntityValidator;
import com.linkedin.metadata.validator.RelationshipValidator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.time.StopWatch;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;

import static com.linkedin.metadata.dao.Neo4jUtil.*;
import static com.linkedin.metadata.dao.utils.ModelUtils.*;
import static com.linkedin.metadata.dao.utils.RecordUtils.*;


/**
 * An Neo4j implementation of {@link BaseGraphWriterDAO}.
 */
@Slf4j
public class Neo4jGraphWriterDAO extends BaseGraphWriterDAO {
  /**
   * Event listening interface to consume certain neo4j events and report metrics to some specific metric recording
   * framework.
   *
   * <p>This allows for recording lower level metrics than just recording how long these method calls take; there is
   * other overhead associated with the methods in this class, and these callbacks attempt to more accurately reflect
   * how long neo4j transactions are taking.
   */
  public interface MetricListener {
    /**
     * Event when entities are successfully added to the neo4j graph.
     *
     * @param entityCount how many entities were added in this transaction
     * @param updateTimeMs how long the update took in total (across all retries)
     * @param retries how many retries were needed before the update was successful (0 means first attempt was a
     *     success)
     */
    void onEntitiesAdded(int entityCount, long updateTimeMs, int retries);

    /**
     * Event when relationships are successfully added to the neo4j graph.
     *
     * @param relationshipCount how many relationships were added in this transaction
     * @param updateTimeMs how long the update took in total (across all retries)
     * @param retries how many retries were needed before the update was successful (0 means first attempt was a
     *     success)
     */
    void onRelationshipsAdded(int relationshipCount, long updateTimeMs, int retries);

    /**
     * Event when entities are successfully removed from the neo4j graph.
     *
     * @param entityCount how many entities were removed in this transaction
     * @param updateTimeMs how long the update took in total (across all retries)
     * @param retries how many retries were needed before the update was successful (0 means first attempt was a
     *     success)
     */
    void onEntitiesRemoved(int entityCount, long updateTimeMs, int retries);

    /**
     * Event when relationships are successfully removed from the neo4j graph.
     *
     * @param relationshipCount how many relationships were added in this transaction
     * @param updateTimeMs how long the update took in total (across all retries)
     * @param retries how many retries were needed before the update was successful (0 means first attempt was a
     *     success)
     */
    void onRelationshipsRemoved(int relationshipCount, long updateTimeMs, int retries);
  }

  private static final class DelegateMetricListener implements MetricListener {
    private final Set<MetricListener> _metricListeners = new HashSet<>();

    void addMetricListener(@Nonnull MetricListener metricListener) {
      _metricListeners.add(metricListener);
    }

    @Override
    public void onEntitiesAdded(int entityCount, long updateTimeMs, int retries) {
      for (MetricListener m : _metricListeners) {
        m.onEntitiesAdded(entityCount, updateTimeMs, retries);
      }
    }

    @Override
    public void onRelationshipsAdded(int relationshipCount, long updateTimeMs, int retries) {
      for (MetricListener m : _metricListeners) {
        m.onRelationshipsAdded(relationshipCount, updateTimeMs, retries);
      }
    }

    @Override
    public void onEntitiesRemoved(int entityCount, long updateTimeMs, int retries) {
      for (MetricListener m : _metricListeners) {
        m.onEntitiesRemoved(entityCount, updateTimeMs, retries);
      }
    }

    @Override
    public void onRelationshipsRemoved(int relationshipCount, long updateTimeMs, int retries) {
      for (MetricListener m : _metricListeners) {
        m.onRelationshipsRemoved(relationshipCount, updateTimeMs, retries);
      }
    }
  }

  private static final int MAX_TRANSACTION_RETRY = 3;
  private final Driver _driver;
  private SessionConfig _sessionConfig;
  private static Map<String, String> _urnToEntityMap = null;
  private DelegateMetricListener _metricListener = new DelegateMetricListener();

  public Neo4jGraphWriterDAO(@Nonnull Driver driver) {
    this(driver, SessionConfig.defaultConfig());
  }

  /**
   * WARNING: Do NOT use this! This is not tested yet.
   * Multi-DB support comes with Neo4j 4+.
   * Although DAO works with Neo4j 4+, we can't bump Neo4j test harness to 4+ to test this because it needs Java 11
   * And Java 11 build is blocked by ES7 migration.
   */
  public Neo4jGraphWriterDAO(@Nonnull Driver driver, @Nonnull String databaseName) {
    this(driver, SessionConfig.forDatabase(databaseName));
  }

  public Neo4jGraphWriterDAO(@Nonnull Driver driver, @Nonnull SessionConfig sessionConfig) {
    this(driver, sessionConfig, getAllEntities());
  }

  /* Should only be used for testing */
  public Neo4jGraphWriterDAO(@Nonnull Driver driver, @Nonnull SessionConfig sessionConfig,
                             @Nonnull Set<Class<? extends RecordTemplate>> allEntities) {
    this._driver = driver;
    this._sessionConfig = sessionConfig;
    buildUrnToEntityMap(allEntities);
  }

  public void addMetricListener(@Nonnull MetricListener metricListener) {
    _metricListener.addMetricListener(metricListener);
  }

  @Override
  public <ENTITY extends RecordTemplate> void addEntities(@Nonnull List<ENTITY> entities) {

    for (ENTITY entity1 : entities) {
      EntityValidator.validateEntitySchema(entity1.getClass());
    }
    List<Statement> list = new ArrayList<>();
    for (ENTITY entity : entities) {
      Statement statement = addNode(entity);
      list.add(statement);
    }

    final ExecutionResult e = executeStatements(list);
    log.trace("Added {} entities over {} retries, which took {} millis", entities.size(), e.getTookMs(),
        e.getRetries());
    _metricListener.onEntitiesAdded(entities.size(), e.getTookMs(), e.getRetries());
  }

  @Override
  public <URN extends Urn> void removeEntities(@Nonnull List<URN> urns) {
    List<Statement> list = new ArrayList<>();
    for (URN urn : urns) {
      Statement statement = removeNode(urn);
      list.add(statement);
    }

    final ExecutionResult e = executeStatements(list);
    log.trace("Removed {} entities over {} retries, which took {} millis", urns.size(), e.getTookMs(),
        e.getRetries());
    _metricListener.onEntitiesRemoved(urns.size(), e.getTookMs(), e.getRetries());
  }

  @Override
  public <RELATIONSHIP extends RecordTemplate> void addRelationships(@Nonnull List<RELATIONSHIP> relationships,
      @Nonnull RemovalOption removalOption) {

    for (RELATIONSHIP relationship : relationships) {
      RelationshipValidator.validateRelationshipSchema(relationship.getClass());
    }

    final ExecutionResult e = executeStatements(addEdges(relationships, removalOption));
    log.trace("Added {} relationships over {} retries, which took {} millis", relationships.size(), e.getTookMs(),
        e.getRetries());
    _metricListener.onRelationshipsAdded(relationships.size(), e.getTookMs(), e.getRetries());
  }

  @Override
  public <RELATIONSHIP extends RecordTemplate> void removeRelationships(@Nonnull List<RELATIONSHIP> relationships) {

    for (RELATIONSHIP relationship : relationships) {
      RelationshipValidator.validateRelationshipSchema(relationship.getClass());
    }
    List<Statement> list = new ArrayList<>();
    for (RELATIONSHIP relationship : relationships) {
      Statement statement = removeEdge(relationship);
      list.add(statement);
    }

    final ExecutionResult e = executeStatements(list);
    log.trace("Removed {} relationships over {} retries, which took {} millis", relationships.size(), e.getTookMs(),
        e.getRetries());
    _metricListener.onRelationshipsRemoved(relationships.size(), e.getTookMs(), e.getRetries());
  }

  @AllArgsConstructor
  @Data
  private static final class ExecutionResult {
    private long tookMs;
    private int retries;
  }

  /**
   * Executes a list of statements with parameters in one transaction.
   *
   * @param statements List of statements with parameters to be executed in order
   */
  private ExecutionResult executeStatements(@Nonnull List<Statement> statements) {
    int retry = 0;
    final StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    Exception lastException;
    try (final Session session = _driver.session(_sessionConfig)) {
      do {
        try {
          session.writeTransaction(tx -> {
            for (Statement statement : statements) {
              tx.run(statement.getCommandText(), statement.getParams());
            }
            return 0;
          });
          lastException = null;
          break;
        } catch (Neo4jException e) {
          lastException = e;
        }
      } while (++retry <= MAX_TRANSACTION_RETRY);
    }

    if (lastException != null) {
      throw new RetryLimitReached("Failed to execute Neo4j write transaction after "
          + MAX_TRANSACTION_RETRY + " retries", lastException);
    }

    stopWatch.stop();
    return new ExecutionResult(stopWatch.getTime(), retry);
  }

  /**
   * Run a query statement with parameters and return StatementResult.
   *
   * @param statement a statement with parameters to be executed
   */
  @Nonnull
  private List<Record> runQuery(@Nonnull Statement statement) {
    try (final Session session = _driver.session(_sessionConfig)) {
      return session.run(statement.getCommandText(), statement.getParams()).list();
    }
  }

  // used in testing
  @Nonnull
  Optional<Map<String, Object>> getNode(@Nonnull Urn urn) {
    List<Map<String, Object>> nodes = getAllNodes(urn);
    if (nodes.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(nodes.get(0));
  }

  // used in testing
  @Nonnull
  List<Map<String, Object>> getAllNodes(@Nonnull Urn urn) {
    final String matchTemplate = "MATCH (node%s {urn: $urn}) RETURN node";

    final String sourceType = getNodeType(urn);
    final String statement = String.format(matchTemplate, sourceType);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    final List<Record> result = runQuery(buildStatement(statement, params));
    return result.stream().map(record -> record.values().get(0).asMap()).collect(Collectors.toList());
  }

  // used in testing
  @Nonnull
  <RELATIONSHIP extends RecordTemplate> List<Map<String, Object>> getEdges(@Nonnull RELATIONSHIP relationship) {
    final Urn sourceUrn = getSourceUrnFromRelationship(relationship);
    final Urn destinationUrn = getDestinationUrnFromRelationship(relationship);
    final String relationshipType = getType(relationship);

    final String sourceType = getNodeType(sourceUrn);
    final String destinationType = getNodeType(destinationUrn);

    final String matchTemplate =
        "MATCH (source%s {urn: $sourceUrn})-[r:%s]->(destination%s {urn: $destinationUrn}) RETURN r";
    final String statement = String.format(matchTemplate, sourceType, relationshipType, destinationType);

    final Map<String, Object> params = new HashMap<>();
    params.put("sourceUrn", sourceUrn.toString());
    params.put("destinationUrn", destinationUrn.toString());

    final List<Record> result = runQuery(buildStatement(statement, params));
    return result.stream().map(record -> record.values().get(0).asMap()).collect(Collectors.toList());
  }

  // used in testing
  @Nonnull
  <RELATIONSHIP extends RecordTemplate> List<Map<String, Object>> getEdgesFromSource(
      @Nonnull Urn sourceUrn, @Nonnull Class<RELATIONSHIP> relationshipClass) {
    final String relationshipType = getType(relationshipClass);
    final String sourceType = getNodeType(sourceUrn);

    final String matchTemplate = "MATCH (source%s {urn: $sourceUrn})-[r:%s]->() RETURN r";
    final String statement = String.format(matchTemplate, sourceType, relationshipType);

    final Map<String, Object> params = new HashMap<>();
    params.put("sourceUrn", sourceUrn.toString());

    final List<Record> result = runQuery(buildStatement(statement, params));
    return result.stream().map(record -> record.values().get(0).asMap()).collect(Collectors.toList());
  }

  @Nonnull
  private <ENTITY extends RecordTemplate> Statement addNode(@Nonnull ENTITY entity) {
    final Urn urn = getUrnFromEntity(entity);
    final String nodeType = getNodeType(urn);

    // Use += to ensure this doesn't override the node but merges in the new properties to allow for partial updates.
    final String mergeTemplate = "MERGE (node%s {urn: $urn}) SET node += $properties RETURN node";
    final String statement = String.format(mergeTemplate, nodeType);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());
    final Map<String, Object> props = entityToNode(entity);
    props.remove("urn"); // no need to set twice (this is implied by MERGE), and they can be quite long.
    params.put("properties", props);

    return buildStatement(statement, params);
  }

  @Nonnull
  private <URN extends Urn> Statement removeNode(@Nonnull URN urn) {
    // also delete any relationship going to or from it
    final String nodeType = getNodeType(urn);

    final String matchTemplate = "MATCH (node%s {urn: $urn}) DETACH DELETE node";
    final String statement = String.format(matchTemplate, nodeType);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    return buildStatement(statement, params);
  }

  /**
   * Gets Node based on Urn, if not exist, creates placeholder node.
   */
  @Nonnull
  private Statement getOrInsertNode(@Nonnull Urn urn) {
    final String nodeType = getNodeType(urn);

    final String mergeTemplate = "MERGE (node%s {urn: $urn}) RETURN node";
    final String statement = String.format(mergeTemplate, nodeType);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    return buildStatement(statement, params);
  }

  @Nonnull
  private <RELATIONSHIP extends RecordTemplate>
    List<Statement> addEdges(@Nonnull List<RELATIONSHIP> relationships, @Nonnull RemovalOption removalOption) {

    // if no relationships, return
    if (relationships.isEmpty()) {
      return Collections.emptyList();
    }

    final List<Statement> statements = new ArrayList<>();

    // remove existing edges according to RemovalOption
    final Urn source0Urn = getSourceUrnFromRelationship(relationships.get(0));
    final Urn destination0Urn = getDestinationUrnFromRelationship(relationships.get(0));
    final String relationType = getType(relationships.get(0));

    final String sourceType = getNodeType(source0Urn);
    final String destinationType = getNodeType(destination0Urn);

    final Map<String, Object> params = new HashMap<>();

    if (removalOption == RemovalOption.REMOVE_ALL_EDGES_FROM_SOURCE) {
      checkSameUrn(relationships, SOURCE_FIELD, source0Urn);

      final String removeTemplate = "MATCH (source%s {urn: $urn})-[relation:%s]->() DELETE relation";
      final String statement = String.format(removeTemplate, sourceType, relationType);

      params.put("urn", source0Urn.toString());

      statements.add(buildStatement(statement, params));
    } else if (removalOption == RemovalOption.REMOVE_ALL_EDGES_TO_DESTINATION) {
      checkSameUrn(relationships, DESTINATION_FIELD, destination0Urn);

      final String removeTemplate = "MATCH ()-[relation:%s]->(destination%s {urn: $urn}) DELETE relation";
      final String statement = String.format(removeTemplate, relationType, destinationType);

      params.put("urn", destination0Urn.toString());

      statements.add(buildStatement(statement, params));
    } else if (removalOption == RemovalOption.REMOVE_ALL_EDGES_FROM_SOURCE_TO_DESTINATION) {
      checkSameUrn(relationships, SOURCE_FIELD, source0Urn);
      checkSameUrn(relationships, DESTINATION_FIELD, destination0Urn);

      final String removeTemplate =
          "MATCH (source%s {urn: $sourceUrn})-[relation:%s]->(destination%s {urn: $destinationUrn}) DELETE relation";
      final String statement = String.format(removeTemplate, sourceType, relationType, destinationType);

      params.put("sourceUrn", source0Urn.toString());
      params.put("destinationUrn", destination0Urn.toString());

      statements.add(buildStatement(statement, params));
    }

    for (RELATIONSHIP relationship : relationships) {
      final Urn srcUrn = getSourceUrnFromRelationship(relationship);
      final Urn destUrn = getDestinationUrnFromRelationship(relationship);
      final String sourceNodeType = getNodeType(srcUrn);
      final String destinationNodeType = getNodeType(destUrn);

      // Add/Update source & destination node first
      statements.add(getOrInsertNode(srcUrn));
      statements.add(getOrInsertNode(destUrn));

      // Add/Update relationship
      final String mergeRelationshipTemplate =
          "MATCH (source%s {urn: $sourceUrn}),(destination%s {urn: $destinationUrn}) MERGE (source)-[r:%s]->(destination) SET r += $properties";
      final String statement =
          String.format(mergeRelationshipTemplate, sourceNodeType, destinationNodeType, getType(relationship));

      final Map<String, Object> paramsMerge = new HashMap<>();
      paramsMerge.put("sourceUrn", srcUrn.toString());
      paramsMerge.put("destinationUrn", destUrn.toString());
      paramsMerge.put("properties", relationshipToEdge(relationship));

      statements.add(buildStatement(statement, paramsMerge));
    }

    return statements;
  }

  private <T extends RecordTemplate> void checkSameUrn(@Nonnull List<T> records, @Nonnull String field,
      @Nonnull Urn compare) {
    for (T relation : records) {
      if (!compare.equals(getRecordTemplateField(relation, field, Urn.class))) {
        throw new IllegalArgumentException("Records have different " + field + " urn");
      }
    }
  }

  @Nonnull
  private <RELATIONSHIP extends RecordTemplate> Statement removeEdge(@Nonnull RELATIONSHIP relationship) {

    final Urn sourceUrn = getSourceUrnFromRelationship(relationship);
    final Urn destinationUrn = getDestinationUrnFromRelationship(relationship);

    final String sourceType = getNodeType(sourceUrn);
    final String destinationType = getNodeType(destinationUrn);

    final String removeMatchTemplate =
        "MATCH (source%s {urn: $sourceUrn})-[relation:%s %s]->(destination%s {urn: $destinationUrn}) DELETE relation";
    final String criteria = relationshipToCriteria(relationship);
    final String statement =
        String.format(removeMatchTemplate, sourceType, getType(relationship), criteria, destinationType);

    final Map<String, Object> params = new HashMap<>();
    params.put("sourceUrn", sourceUrn.toString());
    params.put("destinationUrn", destinationUrn.toString());

    return buildStatement(statement, params);
  }

  // visible for testing
  @Nonnull
  Statement buildStatement(@Nonnull String queryTemplate, @Nonnull Map<String, Object> params) {
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      String k = entry.getKey();
      Object v = entry.getValue();
      params.put(k, toPropertyValue(v));
    }
    return new Statement(queryTemplate, params);
  }

  @Nonnull
  private Object toPropertyValue(@Nonnull Object obj) {
    if (obj instanceof Urn) {
      return obj.toString();
    }
    return obj;
  }

  @Nonnull
  public String getNodeType(@Nonnull Urn urn) {
    return ":" + _urnToEntityMap.getOrDefault(urn.getEntityType(), "UNKNOWN");
  }

  @Nonnull
  private Map<String, String> buildUrnToEntityMap(@Nonnull Set<Class<? extends RecordTemplate>> entitiesSet) {
    if (_urnToEntityMap == null) {
      Map<String, String> map = new HashMap<>();
      for (Class<? extends RecordTemplate> entity : entitiesSet) {
        if (map.put(getEntityTypeFromUrnClass(urnClassForEntity(entity)), getType(entity)) != null) {
          throw new IllegalStateException("Duplicate key");
        }
      }
      _urnToEntityMap = map;
    }
    return _urnToEntityMap;
  }
}
