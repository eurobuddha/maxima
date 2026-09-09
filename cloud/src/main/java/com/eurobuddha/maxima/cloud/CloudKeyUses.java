package com.eurobuddha.maxima.cloud;

import com.eurobuddha.wallet.FileKeyUses;
import java.io.File;

/** Compatibility entry point; the durable counter implementation is shared with the other JVM host. */
public final class CloudKeyUses extends FileKeyUses {
    public CloudKeyUses(File walletDir, String namespace) { super(walletDir, namespace); }
}
