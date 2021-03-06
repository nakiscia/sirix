package org.sirix.index;

import com.google.common.collect.ImmutableSet;
import org.brackit.xquery.atomic.Str;
import org.brackit.xquery.xdm.Type;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sirix.JsonTestHelper;
import org.sirix.index.avltree.AVLTreeReader;
import org.sirix.index.avltree.keyvalue.CASValue;
import org.sirix.index.avltree.keyvalue.NodeReferences;
import org.sirix.service.json.shredder.JsonShredder;
import org.sirix.service.xml.shredder.InsertPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.brackit.xquery.util.path.Path.parse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class JsonAVLTreeIntegrationTest {
  private static final Path JSON = Paths.get("src", "test", "resources", "json");

  @Before
  public void setUp() {
    JsonTestHelper.deleteEverything();
  }

  @After
  public void tearDown() {
    JsonTestHelper.closeEverything();
  }

  @Test
  public void testCreateCASIndexWhileListeningAndPathIndexOnDemand() {
    test("abc-location-stations.json");
  }

  private void test(String jsonFile) {
    final var jsonPath = JSON.resolve(jsonFile);
    final var database = JsonTestHelper.getDatabase(JsonTestHelper.PATHS.PATH1.getFile());
    try (final var manager = database.openResourceManager(JsonTestHelper.RESOURCE);
         final var trx = manager.beginNodeTrx()) {
      var indexController =
          manager.getWtxIndexController(trx.getRevisionNumber() - 1);

      final var pathToFeatureType = parse("/features/__array__/type");

      final var idxDefOfFeatureType = IndexDefs.createCASIdxDef(false, Type.STR,
          Collections.singleton(pathToFeatureType), 0);

      indexController.createIndexes(ImmutableSet.of(idxDefOfFeatureType), trx);

      final var shredder = new JsonShredder.Builder(trx, JsonShredder.createFileReader(jsonPath),
          InsertPosition.AS_FIRST_CHILD).commitAfterwards().build();
      shredder.call();

      final var indexDef = indexController.getIndexes().getIndexDef(0, IndexType.CAS);

      AVLTreeReader<CASValue, NodeReferences> reader =
          AVLTreeReader.getInstance(trx.getPageTrx(), indexDef.getType(), indexDef.getID());

      final var pathNodeKeys = trx.getPathSummary().getPCRsForPath(pathToFeatureType, false);

      assertEquals(1, pathNodeKeys.size());

      final var references = reader.get(new CASValue(new Str("Feature"), Type.STR, pathNodeKeys.iterator().next()), SearchMode.EQUAL);

      assertTrue(references.isPresent());
      assertEquals(53, references.get().getNodeKeys().size());

      final var pathToName = parse("/features/__array__/properties/name");
      final var idxDefOfPathToName = IndexDefs.createPathIdxDef(Collections.singleton(pathToName), 1);

      indexController.createIndexes(ImmutableSet.of(idxDefOfPathToName), trx);

      final var pathIndexDef = indexController.getIndexes().getIndexDef(1, IndexType.PATH);

      final var index = indexController.openPathIndex(trx.getPageTrx(), pathIndexDef, null);

      index.forEachRemaining(nodeReferences -> {
        assertEquals(53, nodeReferences.getNodeKeys().size());
        for (final long nodeKey : nodeReferences.getNodeKeys()) {
          trx.moveTo(nodeKey);
          assertEquals("name", trx.getName().getLocalName());
        }
      });
    }
  }
}
