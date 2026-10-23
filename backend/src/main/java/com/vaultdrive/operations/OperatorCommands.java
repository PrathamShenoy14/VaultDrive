package com.vaultdrive.operations;

import com.vaultdrive.consumer.RetainedEventReplay;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import java.util.*;

/** No HTTP or remote admin surface. One bounded command per explicitly isolated process. */
@Component
@ConditionalOnProperty(name = "vaultdrive.operator.enabled", havingValue = "true")
public class OperatorCommands implements ApplicationRunner {
    private final OperatorTransactions tx;
    private final RetainedEventReplay replay;
    private final OperationalState state;
    private final ConfigurableApplicationContext context;
    public OperatorCommands(OperatorTransactions tx, RetainedEventReplay replay, OperationalState state, ConfigurableApplicationContext context) {
        this.tx=tx; this.replay=replay; this.state=state; this.context=context;
    }
    public void run(ApplicationArguments args) {
        try {
            String command=one(args,"operator.command");
            if (command.equals("inspect")) { System.out.println("OPERATOR work="+state.inspect()); return; }
            String incident=one(args,"operator.incident");
            if (!incident.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IllegalArgumentException("Incident code required");
            String[] values=one(args,"operator.ids").split(",",-1);
            if (values.length<1 || values.length>20) throw new IllegalArgumentException("Supply 1..20 exact UUIDs");
            var ids=Arrays.stream(values).map(value -> {
                UUID id = UUID.fromString(value);
                if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical UUID required");
                return id;
            }).toList();
            if (new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("Duplicate targets");
            if (!Set.of("replay-event","extend-job").contains(command)) throw new IllegalArgumentException("Unknown operator command");
            int additional=command.equals("extend-job")?Integer.parseInt(one(args,"operator.additional-failures")):0;
            if (command.equals("extend-job") && (additional<1 || additional>10)) throw new IllegalArgumentException("Additional failures must be 1..10");
            for (UUID id:ids) {
                UUID action=UUID.randomUUID();
                if (command.equals("extend-job")) tx.extendJob(action,id,incident,additional);
                else {
                    tx.beginReplay(action,id,incident);
                    try { replay.replay(id); tx.finishReplay(action,true); }
                    catch (RuntimeException uncertain) { tx.finishReplay(action,false); throw new IllegalStateException("Replay uncertain; inspect action "+action); }
                }
                System.out.println("OPERATOR action="+action+" target="+id+" outcome=SUCCEEDED");
            }
        } finally { context.close(); }
    }
    private static String one(ApplicationArguments args,String key) {
        var values=args.getOptionValues(key);
        if (values==null || values.size()!=1 || values.getFirst().isBlank()) throw new IllegalArgumentException("Required option: "+key);
        return values.getFirst();
    }
}
