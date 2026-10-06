package mx.endcom.hermes.banxicosim.spei;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Spec 003: intercepta cada byte mandado por el socket SPEI principal para llevar el historial de
 * envío que {@code SpeiSession.handleReenvio} necesita -- ver {@link SentHistory}. Se coloca entre
 * {@code DataOutputStream} y {@link ThrottledOutputStream} (spec 005) en la cadena de streams de
 * {@code SpeiSession.run()}, así que registra exactamente los mismos bytes que terminan saliendo
 * por el socket, en el mismo orden.
 *
 * <p>Solo overridea {@link #write(byte[], int, int)} por el mismo motivo que
 * {@link ThrottledOutputStream}: {@code Frame.writeTo} siempre manda el frame completo de una
 * sola vez con {@code out.write(byte[])}, nunca byte a byte.</p>
 */
final class RecordingOutputStream extends OutputStream {

	private final OutputStream real;
	private final SentHistory history;

	RecordingOutputStream(OutputStream real, SentHistory history) {
		this.real = real;
		this.history = history;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		history.append(b, off, len);
		real.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		real.flush();
	}

	@Override
	public void close() throws IOException {
		real.close();
	}
}
