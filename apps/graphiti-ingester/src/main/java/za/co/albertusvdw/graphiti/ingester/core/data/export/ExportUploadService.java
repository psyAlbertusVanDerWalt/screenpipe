package za.co.albertusvdw.graphiti.ingester.core.data.export;

/**
 * Accepts export files pushed by the workstation.
 *
 * <p>The workstation pushes rather than the server pulling. That keeps the workstation with
 * no inbound exposure at all — no SSH service, no extra local account, no NTFS ACL carve-out,
 * no firewall opening — which is a materially smaller attack surface than any pull design,
 * and it removes an entire class of Windows-side breakage from the pipeline.
 *
 * <p>Losing an upload window does not matter: the ingest ledger is keyed on a deterministic
 * episode key, so re-sending a day's file is a no-op.
 */
public interface ExportUploadService {

    /**
     * Store one day's export file.
     *
     * @param fileName the client-supplied name — untrusted, validated before use
     * @param content  raw JSONL bytes
     * @return bytes written
     */
    long store(String fileName, byte[] content);
}
