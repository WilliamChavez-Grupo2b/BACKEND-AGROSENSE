package com.agrosense.backend.pattern.behavioral.mediator;

/**
 * Mediator: the only one the TCP client talks to. Esp32TcpIngestionService implements it and coordinates the
 * colleagues (the client, the parser and its chain, the aggregator, the feed and its observers, and the ingestion
 * facade), which never call one another: the client does not know there is a parser, nor the parser an aggregator.
 */
public interface Esp32Mediator {

    void connectionOpened();

    void lineArrived(String line);

    void connectionLost();
}
