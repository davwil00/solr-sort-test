package poc;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.distance.DistanceUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SortOrderWithEmbeddedSolrTest {

    private static EmbeddedSolrServer server;

    @BeforeAll
    public static void setUp() throws Exception {
        Path pathToBeDeleted = Paths.get("/tmp/solr-data");
        if (Files.exists(pathToBeDeleted)) {
            Files.walk(pathToBeDeleted)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
        interceptCalls();

        CoreContainer container = new CoreContainer(
                new NodeConfig.NodeConfigBuilder("poc", Paths.get("src/test/resources/solr"))
                        .build()
        );
        container.load();
        server = new EmbeddedSolrServer(container, "poc");
    }

    public static void interceptCalls() {
        ByteBuddyAgent.install();
        new ByteBuddy()
                .redefine(DistanceUtils.class)
                .method(ElementMatchers.named("distHaversineRAD"))
                .intercept(MethodDelegation.to(CountingDistanceUtils.class))
                .make()
                .load(
                        DistanceUtils.class.getClassLoader(),
                        ClassReloadingStrategy.fromInstalledAgent()
                );
    }

    @Test
    public void runQueryWithSingleLocationSort() throws Exception {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("ID", "1");
        doc.setField("LOCATION_MULTI", new String[] { "1.1,2.2", "2.2,2.3" });
        doc.setField("LOCATION_SINGLE", "1.1,2.2");
        server.add(doc);
        server.commit();

        SolrQuery query = new SolrQuery();
        query.setQuery("*:*");
        query.set("pt", "3.3,3.4");
        query.set("sfield", "LOCATION_MULTI");
        query.setSort(
                "min(" +
                    "if(" +
                        "lte(20,geodist(LOCATION_SINGLE,1.1,2.2))," +
                        "13," +
                        "15" +
                    ")," +
                    "13," +
                    "27" +
                ")",
                SolrQuery.ORDER.asc);

        QueryResponse qRespTest = server.query(query);
        var results = qRespTest.getResults();
        assertEquals(1, results.size());
        System.out.println(CountingDistanceUtils.count + " calls");
    }

    @Test
    public void runQueryWithMultiLocationSort() throws Exception {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("ID", "1");
        doc.setField("LOCATION_MULTI", new String[] { "1.1,2.2", "2.2,2.3" });
        doc.setField("LOCATION_SINGLE", "1.1,2.2");
        server.add(doc);
        server.commit();

        SolrQuery query = new SolrQuery();
        query.setQuery("*:*");
        query.set("pt", "3.3,3.4");
        query.set("sfield", "LOCATION_MULTI");
        query.setSort(
                "min(" +
                    "if(" +
                        "lte(20,geodist())," +
                            "13," +
                            "15" +
                        ")," +
                        "13," +
                        "27" +
                    ")",
                SolrQuery.ORDER.asc);

        QueryResponse qRespTest = server.query(query);
        var results = qRespTest.getResults();
        assertEquals(1, results.size());
        System.out.println(CountingDistanceUtils.count + " calls");
    }

    public static class CountingDistanceUtils {
        public static AtomicInteger count = new AtomicInteger(0);
        public static double distHaversineRAD(double lat1, double lon1, double lat2, double lon2) {
            count.getAndIncrement();
            return 1.0;
        }
    }
}


