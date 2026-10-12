package mx.endcom.hermes.banxicosim.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector JSON mínimo, escrito a mano -- misma razón que {@link Json} (ver AGENTS.md &sect;4, "sin
 * frameworks"). Solo soporta lo que necesitan los endpoints de la API de control que aceptan
 * cuerpo: objetos planos de {@link String}/{@link Number}/{@link Boolean}, arreglos de esos
 * mismos valores (incluido arreglos de objetos, para "entradas" de {@code POST /pagos/cargos}), y
 * un nivel de objeto anidado (para el mapa "campos" de un abono con tipo de pago arbitrario). No
 * es un parser JSON de propósito general -- si algún endpoint necesita más (más niveles de
 * anidamiento del que ya soporta), hay que ampliarlo aquí, no reintroducir una librería.
 *
 * <p><b>Corregido 2026-10-12:</b> {@code parseValue} no tenía ningún caso para {@code [} -- todo
 * arreglo caía al fallback de {@link #parseNumber()} y tronaba con "valor inesperado". Esto hacía
 * que {@code POST /pagos/cargos} (su único body documentado trae {@code "entradas": [...]}) fuera
 * imposible de llamar tal como su propio Javadoc lo describe -- confirmado en vivo contra el
 * simulador real (spec 014, prueba de fase 5). No era un caso límite sin cubrir, era el único
 * camino documentado del endpoint, ya roto antes de esta corrección.</p>
 */
final class JsonReader {

	private final String src;
	private int pos;

	private JsonReader(String src) {
		this.src = src;
	}

	/** Parsea un objeto JSON plano en la raíz. Lanza {@link IllegalArgumentException} si el
	 *  cuerpo no es un objeto JSON válido según lo que este lector soporta. */
	static Map<String, Object> readObject(String body) {
		JsonReader r = new JsonReader(body == null ? "" : body);
		r.skipWs();
		if (r.pos >= r.src.length()) {
			return Map.of();
		}
		Map<String, Object> result = r.parseObject();
		r.skipWs();
		return result;
	}

	private Map<String, Object> parseObject() {
		expect('{');
		Map<String, Object> map = new LinkedHashMap<>();
		skipWs();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWs();
			String key = parseString();
			skipWs();
			expect(':');
			skipWs();
			Object value = parseValue();
			map.put(key, value);
			skipWs();
			char c = next();
			if (c == '}') {
				break;
			}
			if (c != ',') {
				throw new IllegalArgumentException("JSON inválido: se esperaba ',' o '}' en la posición " + pos);
			}
		}
		return map;
	}

	private Object parseValue() {
		char c = peek();
		if (c == '"') {
			return parseString();
		}
		if (c == '{') {
			return parseObject();
		}
		if (c == '[') {
			return parseArray();
		}
		if (c == 't' || c == 'f') {
			return parseBoolean();
		}
		if (c == 'n') {
			expectLiteral("null");
			return null;
		}
		return parseNumber();
	}

	private List<Object> parseArray() {
		expect('[');
		List<Object> list = new ArrayList<>();
		skipWs();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipWs();
			list.add(parseValue());
			skipWs();
			char c = next();
			if (c == ']') {
				break;
			}
			if (c != ',') {
				throw new IllegalArgumentException("JSON inválido: se esperaba ',' o ']' en la posición " + pos);
			}
		}
		return list;
	}

	private String parseString() {
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				break;
			}
			if (c == '\\') {
				char esc = next();
				switch (esc) {
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					case '/' -> sb.append('/');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						String hex = src.substring(pos, pos + 4);
						pos += 4;
						sb.append((char) Integer.parseInt(hex, 16));
					}
					default -> throw new IllegalArgumentException("Escape JSON desconocido: \\" + esc);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private Boolean parseBoolean() {
		if (peek() == 't') {
			expectLiteral("true");
			return Boolean.TRUE;
		}
		expectLiteral("false");
		return Boolean.FALSE;
	}

	private Number parseNumber() {
		int start = pos;
		while (pos < src.length() && "-+.0123456789eE".indexOf(src.charAt(pos)) >= 0) {
			pos++;
		}
		String num = src.substring(start, pos);
		if (num.isEmpty()) {
			throw new IllegalArgumentException("JSON inválido: valor inesperado en la posición " + pos);
		}
		if (num.contains(".") || num.contains("e") || num.contains("E")) {
			return Double.parseDouble(num);
		}
		return Long.parseLong(num);
	}

	private void expectLiteral(String literal) {
		if (!src.regionMatches(pos, literal, 0, literal.length())) {
			throw new IllegalArgumentException("JSON inválido: se esperaba '" + literal + "' en la posición " + pos);
		}
		pos += literal.length();
	}

	private void expect(char c) {
		char actual = next();
		if (actual != c) {
			throw new IllegalArgumentException("JSON inválido: se esperaba '" + c + "' pero vino '" + actual + "'");
		}
	}

	private char next() {
		if (pos >= src.length()) {
			throw new IllegalArgumentException("JSON inválido: fin de cadena inesperado");
		}
		return src.charAt(pos++);
	}

	private char peek() {
		if (pos >= src.length()) {
			throw new IllegalArgumentException("JSON inválido: fin de cadena inesperado");
		}
		return src.charAt(pos);
	}

	private void skipWs() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}
}
