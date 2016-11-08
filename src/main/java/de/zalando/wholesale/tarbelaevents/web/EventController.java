package de.zalando.wholesale.tarbelaevents.web;


import de.zalando.wholesale.tarbelaevents.api.event.model.BunchOfEventUpdatesDTO;
import de.zalando.wholesale.tarbelaevents.api.event.model.BunchOfEventsDTO;
import de.zalando.wholesale.tarbelaevents.service.EventLogService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping(value = "/events")
public class EventController {

    public static final String CONTENT_TYPE_PROBLEM = "application/problem+json";
    public static final String CONTENT_TYPE_EVENT_LIST = "application/x.tarbela.event-list+json";
    public static final String CONTENT_TYPE_EVENT_LIST_UPDATE = "application/x.tarbela.event-list-update+json";

    private EventLogService eventLogService;

    private FlowIdComponent flowIdComponent;

    @Autowired
    public EventController(final EventLogService eventLogService, FlowIdComponent flowIdComponent) {
        this.eventLogService = eventLogService;
        this.flowIdComponent = flowIdComponent;
    }

    @RequestMapping(method = RequestMethod.GET, produces = {CONTENT_TYPE_EVENT_LIST, CONTENT_TYPE_PROBLEM})
    public ResponseEntity<BunchOfEventsDTO> eventsGet(
            @RequestParam(value = "cursor", required = false) final String cursor,
            @RequestParam(value = "status", required = false) final String status,
            @RequestParam(value = "limit", required = false) final Integer limit) {

        final BunchOfEventsDTO events = eventLogService.searchEvents(cursor, status, limit);
        return ResponseEntity.status(HttpStatus.OK).body(events);
    }

    @RequestMapping(
        method = RequestMethod.PATCH, consumes = CONTENT_TYPE_EVENT_LIST_UPDATE, produces = CONTENT_TYPE_PROBLEM
    )
    public ResponseEntity<Void> eventsPatch(@RequestBody final BunchOfEventUpdatesDTO updates) {

        eventLogService.updateEvents(updates);

        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @RequestMapping(value = "/snapshots/{event_type:.+}", method = RequestMethod.POST, produces = CONTENT_TYPE_PROBLEM)
    public ResponseEntity<Void> eventsSnapshotPost(
            @PathVariable(value = "event_type") final String eventType) {

        eventLogService.createSnapshotEvents(eventType, flowIdComponent.getXFlowIdValue());

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

}
