package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses;

/** Confirms what was stored, so the uploader can verify rather than assume. */
public record UploadAcceptedResponse(String fileName, long bytesStored) {}
