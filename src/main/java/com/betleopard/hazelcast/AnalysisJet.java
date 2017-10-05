package com.betleopard.hazelcast;

import com.betleopard.JSONSerializable;
import com.betleopard.domain.CentralFactory;
import com.betleopard.domain.Event;
import com.betleopard.domain.Horse;
import com.betleopard.simple.SimpleFactory;
import com.betleopard.simple.SimpleHorseFactory;
import com.hazelcast.core.IMap;
import com.hazelcast.jet.*;
import static com.hazelcast.jet.aggregate.AggregateOperations.counting;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.function.DistributedFunction;
import static com.hazelcast.jet.function.DistributedFunctions.wholeItem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Simple example for getting started - uses Jet adapted from Java 8 
 * collections
 * 
 * @author kittylyst
 */
public class AnalysisJet {

    public final static String EVENTS_BY_NAME = "events_by_name";
    public final static String MULTIPLE = "multiple_winners";

    private final static DistributedSupplier<Long> INITIAL_ZERO = () -> 0L;

    public final static String HISTORICAL = "/historical_races.json";
    public final static Function<Event, Horse> FIRST_PAST_THE_POST = e -> e.getRaces().get(0).getWinner().orElse(Horse.PALE);

    private final static DistributedFunction<Entry<String, Event>, Horse> HORSE_FROM_EVENT = e -> FIRST_PAST_THE_POST.apply(e.getValue());

    private JetInstance jet;

    public static void main(String[] args) throws Exception {
        CentralFactory.setHorses(SimpleHorseFactory.getInstance());
        CentralFactory.setRaces(new SimpleFactory<>());
        final AnalysisJet main = new AnalysisJet();
        
        main.setup();
        try {
            main.go();
            final Map<Horse, Long> multiple = main.getResults();
//            final SimpleHorseFactory fact = SimpleHorseFactory.getInstance();
            System.out.println("Result set size: " + multiple.size());
            for (Horse h : multiple.keySet()) {
//                Horse h = fact.getByID(l);
                System.out.println(h + " : " + multiple.get(h));
            }
        } finally {
            Jet.shutdownAll();
        }
    }

    public void setup() {
        jet = Jet.newJetInstance();

        // Populate the map with data from disc
        final IMap<String, Event> name2Event = jet.getMap(EVENTS_BY_NAME);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(AnalysisJet.class.getResourceAsStream(HISTORICAL), UTF_8))) {
            r.lines().map(l -> JSONSerializable.parse(l, Event::parseBag)).forEach(e -> name2Event.put(e.getName(), e));
        } catch (IOException iox) {
            iox.printStackTrace();
        }
    }

    public Map<Horse, Long> getResults() {
        return jet.getMap(MULTIPLE);
    }

    public void go() throws Exception {
        System.out.print("\nStarting up... ");
        long start = System.nanoTime();

        Pipeline p = buildPipeline();
        p.execute(jet);

        System.out.println("done in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " milliseconds.");
    }

    private static com.hazelcast.jet.Pipeline buildPipeline() {
        final Pipeline p = Pipeline.create();
        final ComputeStage<Horse> c = p.drawFrom(Sources.<String, Event>readMap(EVENTS_BY_NAME))
                .map(HORSE_FROM_EVENT);
  
        // Compute map server side
//        final ComputeStage<Horse> c2 = p.drawFrom(Sources.readMap(EVENTS_BY_NAME, t -> true, HORSE_FROM_EVENT));
        
        final ComputeStage<Entry<Horse, Long>> hl = c.groupBy(wholeItem(), counting())
                .filter(ent -> ent.getValue() > 1);
        
        hl.drainTo(Sinks.writeMap(MULTIPLE));
        return p;
    }

}
