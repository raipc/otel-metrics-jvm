package io.github.raipc.metrics.util;

/**
 * @author Anton Rybochkin
 */
public final class ThreadId {
    private long id;

    public ThreadId setId(long id) {
        this.id = id;
        return this;
    }

    public ThreadId copy() {
        ThreadId result = new ThreadId();
        result.id = this.id;
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ThreadId && id == ((ThreadId) o).id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "ThreadId{id=" + id + '}';
    }
}
