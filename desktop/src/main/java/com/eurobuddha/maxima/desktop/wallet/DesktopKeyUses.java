package com.eurobuddha.maxima.desktop.wallet;

import com.eurobuddha.wallet.FileKeyUses;
import java.io.File;

/** Compatibility entry point; the durable counter implementation is shared with the other JVM host. */
public final class DesktopKeyUses extends FileKeyUses {
    public DesktopKeyUses(File walletDir, String namespace) { super(walletDir, namespace); }
}
