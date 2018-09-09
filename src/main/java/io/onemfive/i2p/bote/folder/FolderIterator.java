package io.onemfive.i2p.bote.folder;

import io.onemfive.i2p.bote.fileencryption.PasswordException;

/**
 * Same as {@link java.util.Iterator}, except <code>hasNext()</code> and
 * </code>next()</code> can throw a <code>PasswordException</code>.
 * @param <T>
 */
public interface FolderIterator<T> {

    boolean hasNext() throws PasswordException;

    T next() throws PasswordException;

    void remove();
}
