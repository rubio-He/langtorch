package ai.knowly.langtorch.store.vectordb;

import ai.knowly.langtorch.processor.EmbeddingProcessor;
import ai.knowly.langtorch.schema.embeddings.EmbeddingInput;
import ai.knowly.langtorch.schema.embeddings.EmbeddingOutput;
import ai.knowly.langtorch.schema.io.DomainDocument;
import ai.knowly.langtorch.schema.io.Metadata;
import ai.knowly.langtorch.store.vectordb.integration.VectorStore;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.PGVectorService;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.SqlCommandProvider;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.schema.PGVectorQueryParameters;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.schema.PGVectorStoreSpec;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.schema.PGVectorValues;
import ai.knowly.langtorch.store.vectordb.integration.pgvector.schema.distance.DistanceStrategy;
import ai.knowly.langtorch.store.vectordb.integration.schema.SimilaritySearchQuery;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Floats;
import com.google.inject.Inject;
import com.pgvector.PGvector;
import lombok.NonNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** A vector store implementation using PostgreSQL and PGVector for storing and querying vectors. */
public class PGVectorStore implements VectorStore {

  private static final int EMBEDDINGS_COLUMN_COUNT = 2;
  private static final int EMBEDDINGS_INDEX_ID = 0;
  private static final int EMBEDDINGS_INDEX_VECTOR = 1;
  private static final int METADATA_COLUMN_COUNT = 4;
  private static final int METADATA_INDEX_ID = 0;
  private static final int METADATA_INDEX_KEY = 1;
  private static final int METADATA_INDEX_VALUE = 2;
  private static final int METADATA_INDEX_VECTOR_ID = 3;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @NonNull private final EmbeddingProcessor embeddingsProcessor;
  private final PGVectorStoreSpec pgVectorStoreSpec;
  private final SqlCommandProvider sqlCommandProvider;
  @NonNull private final PGVectorService pgVectorService;
  private final DistanceStrategy distanceStrategy;

  @Inject
  public PGVectorStore(
      @NonNull EmbeddingProcessor embeddingsProcessor,
      PGVectorStoreSpec pgVectorStoreSpec,
      @NonNull PGVectorService pgVectorService,
      DistanceStrategy distanceStrategy)
      throws SQLException {
    this.distanceStrategy = distanceStrategy;
    this.pgVectorService = pgVectorService;
    this.embeddingsProcessor = embeddingsProcessor;
    this.pgVectorStoreSpec = pgVectorStoreSpec;
    sqlCommandProvider =
        new SqlCommandProvider(
            pgVectorStoreSpec.getDatabaseName(), pgVectorStoreSpec.isOverwriteExistingTables());
    createNecessaryTables();
  }

  private void createNecessaryTables() throws SQLException {
    createEmbeddingsTable();
    createMetadataTable();
  }

  /**
   * Adds a list of documents to the PGVector database.
   *
   * @return true if vectors added successfully, otherwise false
   */
  @Override
  public boolean addDocuments(List<DomainDocument> documents) {
    if (documents.isEmpty()) {
      return true;
    }
    PGVectorQueryParameters pgVectorQueryParameters = getVectorQueryParameters(documents);
    List<PGVectorValues> vectorValues = pgVectorQueryParameters.getVectorValues();

    PreparedStatement insertEmbeddingsStmt;
    PreparedStatement insertMetadataStmt;
    int result;
    int metadataResult;
    try {
      insertEmbeddingsStmt =
          pgVectorService.prepareStatement(
              sqlCommandProvider.getInsertEmbeddingsQuery(
                  pgVectorQueryParameters.getVectorParameters()));
      insertMetadataStmt =
          pgVectorService.prepareStatement(
              sqlCommandProvider.getInsertMetadataQuery(
                  pgVectorQueryParameters.getMetadataParameters()));
      setQueryParameters(vectorValues, insertEmbeddingsStmt, insertMetadataStmt);
      result = insertEmbeddingsStmt.executeUpdate();
      metadataResult = insertMetadataStmt.executeUpdate();
    } catch (SQLException e) {
      logger.atSevere().withCause(e).log("Error with SQL Exception");
      return false;
    }

    return result == vectorValues.size()
        && metadataResult == pgVectorQueryParameters.getMetadataSize();
  }

  /**
   * Performs a similarity search using a vector query and returns a list of pairs containing the
   * schema documents and their corresponding similarity scores.
   */
  @Override
  public List<DomainDocument> similaritySearch(SimilaritySearchQuery similaritySearchQuery) {
    float[] queryVectorValuesAsFloats = getFloatVectorValues(similaritySearchQuery.getQuery());
    double[] queryVectorValuesAsDoubles = getDoubleVectorValues(queryVectorValuesAsFloats);
    List<DomainDocument> documentsWithScores;
    Map<String, DomainDocument> documentsWithScoresMap = new LinkedHashMap<>();
    try {
      PreparedStatement neighborStmt =
          pgVectorService.prepareStatement(
              sqlCommandProvider.getSelectEmbeddingsQuery(
                  distanceStrategy.getSyntax(), similaritySearchQuery.getTopK()));

      neighborStmt.setObject(1, new PGvector(queryVectorValuesAsFloats));
      ResultSet result = neighborStmt.executeQuery();

      while (result.next()) {
        String vectorId = (String) result.getObject(1);
        PGvector pGvector = (PGvector) result.getObject(2);
        String key = (String) result.getObject(3);
        String value = (String) result.getObject(4);

        double[] currentVector = getDoubleVectorValues(pGvector.toArray());

        double score =
            distanceStrategy.calculateDistance(queryVectorValuesAsDoubles, currentVector);

        documentsWithScoresMap.computeIfAbsent(
            vectorId,
            s -> {
              Metadata defaultMetadata = Metadata.builder().build();
              return DomainDocument.builder()
                  .setId(vectorId)
                  .setPageContent("")
                  .setSimilarityScore(Optional.of(score))
                  .setMetadata(defaultMetadata)
                  .build();
            });

        DomainDocument documentWithScore = documentsWithScoresMap.get(vectorId);
        saveValueToMetadataIfPresent(documentWithScore, key, value);
        documentsWithScoresMap.put(
            vectorId, getDocumentWithScoreWithPageContent(documentWithScore, key, value));
      }
      documentsWithScores = new ArrayList<>(documentsWithScoresMap.values());
    } catch (SQLException e) {
      logger.atSevere().withCause(e).log("Error with SQL Exception");
      return new ArrayList<>(documentsWithScoresMap.values());
    }

    return documentsWithScores;
  }

  private void createEmbeddingsTable() throws SQLException {
    pgVectorService.executeUpdate(
        sqlCommandProvider.getCreateEmbeddingsTableQuery(pgVectorStoreSpec.getVectorDimensions()));
  }

  private void createMetadataTable() throws SQLException {
    pgVectorService.executeUpdate(sqlCommandProvider.getCreateMetadataTableQuery());
  }

  private PGVectorQueryParameters getVectorQueryParameters(List<DomainDocument> documents) {
    List<PGVectorValues> vectorValues = new ArrayList<>();
    StringBuilder vectorParameters = new StringBuilder();
    StringBuilder metadataParameters = new StringBuilder();
    int metadataSize = 0;
    for (DomainDocument document : documents) {
      List<Double> vector = createVector(document);
      String id = document.getId().orElse(UUID.randomUUID().toString());

      vectorValues.add(buildPGVectorValues(id, vector, document.getMetadata()));

      vectorParameters.append(getVectorParameters());
      metadataSize += processMetadata(metadataParameters, document.getMetadata());
    }

    trimStringBuilder(vectorParameters);
    trimStringBuilder(metadataParameters);

    return buildPGVectorQueryParameters(
        vectorValues, vectorParameters.toString(), metadataParameters.toString(), metadataSize);
  }

  private PGVectorValues buildPGVectorValues(
      String id, List<Double> vector, Optional<Metadata> metadata) {
    return PGVectorValues.builder()
        .setId(id)
        .setValues(getFloatVectorValues(vector))
        .setMetadata(metadata.orElse(Metadata.builder().build()))
        .build();
  }

  private String getVectorParameters() {
    return "(?, ?), "; // document id and vector
  }

  private int processMetadata(StringBuilder metadataParameters, Optional<Metadata> metadata) {
    int metadataSize = 0;
    if (!metadata.isPresent()) {
      return metadataSize;
    }
    metadataSize += metadata.get().getValue().size();
    for (int i = 0; i < metadata.get().getValue().entrySet().size(); i++) {
      metadataParameters.append("(?, ?, ?, ?), "); // id, key, value, and document id
    }
    return metadataSize;
  }

  private void trimStringBuilder(StringBuilder stringBuilder) {
    int index = stringBuilder.lastIndexOf(", ");
    if (index > 0) {
      stringBuilder.delete(index, stringBuilder.length());
    }
  }

  private PGVectorQueryParameters buildPGVectorQueryParameters(
      List<PGVectorValues> vectorValues,
      String vectorParameters,
      String metadataParameters,
      int metadataSize) {
    return PGVectorQueryParameters.builder()
        .setVectorValues(vectorValues)
        .setVectorParameters(vectorParameters)
        .setMetadataParameters(metadataParameters)
        .setMetadataSize(metadataSize)
        .build();
  }

  private List<Double> createVector(DomainDocument document) {
    EmbeddingOutput embeddingOutput =
        embeddingsProcessor.run(
            EmbeddingInput.builder()
                .setModel(pgVectorStoreSpec.getModel())
                .setInput(Collections.singletonList(document.getPageContent()))
                .build());
    return embeddingOutput.getValue().get(0).getVector();
  }

  private int setMetadataQueryParameters(
      PGVectorValues values, int parameterIndex, PreparedStatement insertStmt) throws SQLException {
    for (Map.Entry<String, String> entry : values.getMetadata().getValue().entrySet()) {
      for (int j = 0; j < METADATA_COLUMN_COUNT; j++) {
        switch (j) {
          case METADATA_INDEX_ID:
            String id = values.getId() + entry.getKey();
            insertStmt.setString(parameterIndex, id);
            break;
          case METADATA_INDEX_KEY:
            insertStmt.setString(parameterIndex, entry.getKey());
            break;
          case METADATA_INDEX_VALUE:
            insertStmt.setString(parameterIndex, entry.getValue());
            break;
          case METADATA_INDEX_VECTOR_ID:
            insertStmt.setString(parameterIndex, values.getId());
            break;
          default:
            logger.atSevere().log("INVALID COLUM INDEX");
        }
        parameterIndex++;
      }
    }
    return parameterIndex;
  }

  private int setVectorQueryParameters(
      PGVectorValues values, int parameterIndex, PreparedStatement insertStmt) throws SQLException {
    for (int i = 0; i < EMBEDDINGS_COLUMN_COUNT; i++) {
      if (i == EMBEDDINGS_INDEX_ID) {
        insertStmt.setString(parameterIndex, values.getId());
      } else if (i == EMBEDDINGS_INDEX_VECTOR) {
        insertStmt.setObject(parameterIndex, new PGvector(values.getValues()));
      }
      parameterIndex++;
    }
    return parameterIndex;
  }

  private void setQueryParameters(
      List<PGVectorValues> vectorValues,
      PreparedStatement insertEmbeddingsStmt,
      PreparedStatement insertMetadataStmt)
      throws SQLException {
    int embeddingParameterIndex = 1;
    int metadataParameterIndex = 1;
    for (PGVectorValues values : vectorValues) {
      embeddingParameterIndex =
          setVectorQueryParameters(values, embeddingParameterIndex, insertEmbeddingsStmt);
      metadataParameterIndex =
          setMetadataQueryParameters(values, metadataParameterIndex, insertMetadataStmt);
    }
  }

  private void saveValueToMetadataIfPresent(DomainDocument document, String key, String value) {
    Optional<Metadata> metadata = document.getMetadata();

    if (!metadata.isPresent() || key == null) return;

    metadata.get().getValue().put(key, value);
  }

  private DomainDocument getDocumentWithScoreWithPageContent(
      DomainDocument documentWithScore, String key, String value) {
    if (key == null) return documentWithScore;
    Optional<String> textKey = pgVectorStoreSpec.getTextKey();

    if (!textKey.isPresent()) return documentWithScore;

    boolean isTextKey = key.equals(textKey.get());
    if (!isTextKey) return documentWithScore;

    return documentWithScore.toBuilder().setPageContent(value).build();
  }

  private float[] getFloatVectorValues(List<Double> vectorValues) {
    return Floats.toArray(vectorValues);
  }

  private double[] getDoubleVectorValues(float[] vectorValues) {
    double[] doubles = new double[vectorValues.length];
    for (int i = 0; i < vectorValues.length; i++) {
      doubles[i] = vectorValues[i];
    }
    return doubles;
  }
}
