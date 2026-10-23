package io.mosip.registration.liveness.core.statusaudit;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.WorkflowType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Test-only AuditSink recording everything it receives (stand-in for Parts 4/5). */
public final class InMemoryAuditSink implements AuditSink {
    public static final class Record {
        public final Event event;
        public final WorkflowType workflow;
        public final String sessionId;
        public final Map<String, String> details;

        Record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
            this.event = event; this.workflow = workflow; this.sessionId = sessionId; this.details = details;
        }

        @Override public String toString() { return event + " " + sessionId + " " + details; }
    }

    public final List<Record> records = Collections.synchronizedList(new ArrayList<Record>());

    @Override
    public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
        records.add(new Record(event, workflow, sessionId, details));
    }

    public List<Record> of(Event e) {
        List<Record> out = new ArrayList<Record>();
        synchronized (records) {
            for (Record r : records) if (r.event == e) out.add(r);
        }
        return out;
    }
}
