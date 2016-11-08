package de.zalando.wholesale.tarbelaevents.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.zalando.wholesale.tarbelaevents.TarbelaProperties;
import de.zalando.wholesale.tarbelaevents.TarbelaSnapshotProvider;
import de.zalando.wholesale.tarbelaevents.api.event.model.BunchOfEventUpdatesDTO;
import de.zalando.wholesale.tarbelaevents.api.event.model.BunchOfEventsDTO;
import de.zalando.wholesale.tarbelaevents.persistance.entity.EventDataOperation;
import de.zalando.wholesale.tarbelaevents.persistance.entity.EventLog;
import de.zalando.wholesale.tarbelaevents.persistance.entity.EventStatus;
import de.zalando.wholesale.tarbelaevents.persistance.repository.EventLogRepository;
import de.zalando.wholesale.tarbelaevents.service.exception.InvalidCursorException;
import de.zalando.wholesale.tarbelaevents.service.exception.InvalidEventIdException;
import de.zalando.wholesale.tarbelaevents.service.exception.UnknownEventIdException;
import de.zalando.wholesale.tarbelaevents.service.exception.ValidationException;
import de.zalando.wholesale.tarbelaevents.service.model.EventPayload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.transaction.Transactional;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Lists.newArrayList;

@Service
@Slf4j
public class EventLogServiceImpl implements EventLogWriter, EventLogService {

    public static final int DEFAULT_LIMIT = 10;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Autowired
    private EventLogMapper eventLogMapper;

    @Autowired
    private TarbelaProperties tarbelaProperties;

    @Autowired
    private TarbelaSnapshotProvider tarbelaSnapshotProvider;

    @Override
    @Transactional
    public void fireCreateEvent(final EventPayload payload, final String flowId) {
        final EventLog eventLog = createEventLog(EventDataOperation.CREATE, payload, flowId);
        eventLogRepository.save(eventLog);
    }

    @Override
    @Transactional
    public void fireUpdateEvent(final EventPayload payload, final String flowId) {
        final EventLog eventLog = createEventLog(EventDataOperation.UPDATE, payload, flowId);
        eventLogRepository.save(eventLog);
    }

    @VisibleForTesting
    EventLog createEventLog(final EventDataOperation dataOp, final EventPayload eventPayload, final String flowId) {
        final EventLog eventLog = new EventLog();
        eventLog.setStatus(EventStatus.NEW.toString());
        eventLog.setEventType(eventPayload.getEventType());
        try {
            eventLog.setEventBodyData(objectMapper.writeValueAsString(eventPayload.getData()));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("could not map object to json: " + eventPayload.getData().toString(), e);
        }

        eventLog.setDataOp(dataOp.toString());
        eventLog.setDataType(eventPayload.getDataType());
        eventLog.setFlowId(flowId);
        return eventLog;
    }

    @Override
    public BunchOfEventsDTO searchEvents(final String cursor, final String status, final Integer limit) {

        final List<EventLog> events = eventLogRepository.search(convertCursorToInteger(cursor),
                status, limit == null ? DEFAULT_LIMIT : limit);

        return eventLogMapper.mapToDTO(events, status, limit, tarbelaProperties.getSinkId());
    }

    @Override
    @Transactional
    public void updateEvents(final BunchOfEventUpdatesDTO updates) {

        final Map<Integer, String> statusById = validateAndBuildStatusByIdMap(updates);

        final List<EventLog> eventLogs = eventLogRepository.findByIdIn(newArrayList(
                    statusById.keySet()));
        final Map<Integer, EventLog> eventsByID = eventLogs.stream().collect(Collectors.toMap(
                    EventLog::getId, event -> event));

        // set new status for event logs
        statusById.keySet().forEach(eventId -> {
            final EventLog event = eventsByID.get(eventId);
            if (event == null) {
                throw new UnknownEventIdException(eventId);
            }

            final String newStatus = statusById.get(eventId);
            event.setStatus(newStatus);

            if (EventStatus.ERROR.name().equals(newStatus)) {
                event.setErrorCount(event.getErrorCount() + 1);
            }
        });

        eventLogRepository.save(eventLogs);
        eventLogRepository.flush();
    }

    private Map<Integer, String> validateAndBuildStatusByIdMap(final BunchOfEventUpdatesDTO updates) {

        final Map<Integer, String> statusById = Maps.newHashMap();
        updates.getEvents().forEach(event -> {

            List<String> validations = newArrayList();

            if (isNullOrEmpty(event.getDeliveryStatus())) {
                validations.add(ValidationErrorMessages.getFieldNullOrEmptyMessage("events.delivery_status"));
            }

            if (isNullOrEmpty(event.getEventId())) {
                validations.add(ValidationErrorMessages.getFieldNullOrEmptyMessage("events.event_id"));
            }

            if (!validations.isEmpty()) {
                throw new ValidationException(validations);
            }

            final Integer eventId;
            try {
                eventId = Integer.valueOf(event.getEventId());
            } catch (NumberFormatException e) {
                throw new InvalidEventIdException(event.getEventId());
            }

            statusById.put(eventId, event.getDeliveryStatus());
        });

        return statusById;
    }

    private Integer convertCursorToInteger(final String cursor) {

        if (isNullOrEmpty(cursor)) {
            return null;
        }

        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new InvalidCursorException(cursor);
        }
    }

    @Override
    @Transactional
    public void createSnapshotEvents(final String eventType, final String flowId) {

        Stream<EventPayload> snapshotItemsStream = tarbelaSnapshotProvider.getSnapshot(eventType);

        Iterators.partition(snapshotItemsStream.iterator(), tarbelaProperties.getSnapshotBatchSize())
                .forEachRemaining(batch -> {
                    final List<EventLog> events = batch.stream()
                            .map(item -> createEventLog(EventDataOperation.SNAPSHOT, item, flowId))
                            .collect(Collectors.toList());
                    eventLogRepository.save(events);
                });

        eventLogRepository.flush();
    }

    static class ValidationErrorMessages {

        private static final String FIELD_NULL_OR_EMPTY_TEMPLATE = "required field {0} null or empty";

        static String getFieldNullOrEmptyMessage(final String fieldName) {
            return MessageFormat.format(FIELD_NULL_OR_EMPTY_TEMPLATE, fieldName);
        }

    }

}
