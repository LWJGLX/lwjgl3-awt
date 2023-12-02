package org.lwjgl.opengl.awt;

public class ContextData {
    public long ctx;
    public GLData caps;

    public ContextData(long ctx, GLData caps) {
	this.ctx = ctx;
	this.caps = caps;
    }
}
