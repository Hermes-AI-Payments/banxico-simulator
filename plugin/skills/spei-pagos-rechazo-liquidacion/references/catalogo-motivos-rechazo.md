# Catálogo de motivos de rechazo/devolución

Fuente: `HERMES-MKI-VOBEDA/01_Conceptos_Transversales_SPEI/Catalogo_Devoluciones.md` (el único
catálogo real disponible), tal como lo implementa `MotivoRechazo.java` del simulador. Es el valor
que `simulator_force_payment_rejection` espera en `motivo`, y el mismo que el simulador ya usa
solo al mapear fallas reales de validación (spec 014 §1) — forzarlo con este skill produce el
mismo `AcuseRecibo` que una falla real de ese motivo, sin que la orden tenga en realidad ese
problema.

| Código | Nombre | Cuándo usarlo para forzar una prueba |
|---|---|---|
| 1 | Cuenta inexistente | Probar reacción ante beneficiario inexistente |
| 2 | Cuenta bloqueada | Cuenta bloqueada del lado receptor |
| 3 | Cuenta cancelada | Cuenta cancelada del lado receptor |
| 5 | Cuenta en otra divisa | Divisa no corresponde |
| 6 | Cuenta no pertenece al receptor | Cuenta no es del banco receptor declarado |
| 14 | Falta información mandatoria | Mismo motivo que un campo obligatorio vacío real (spec 014 §1) |
| 15 | Tipo de pago erróneo | Mismo motivo que un `tipoPg` fuera de catálogo real |
| 16 | Tipo de operación errónea | Mismo motivo que un `tipoOperacion` fuera de catálogo real |
| 17 | Tipo de cuenta no corresponde | Mismo motivo que un `tipoCta*` fuera de catálogo real |
| 19 | Carácter inválido | **Motivo genérico/neutral** — el que ya usa el simulador como fallback para cualquier falla de formato/contenido sin motivo más específico. Úsalo si el usuario solo quiere "un rechazo cualquiera" sin importar el motivo exacto |
| 20 | Excede límite de saldo autorizado | Probar reacción ante límite de saldo |
| 21 | Excede límite de abonos del mes | Probar reacción ante límite mensual |
| 22 | Número celular no registrado | Pagos con celular (CoDi/afines) |
| 23 | Cuenta adicional no recibe pagos | Restricción de cuenta adicional |
| 24 | Estructura de información adicional incorrecta | Mismo motivo que un conteo de campos de detalle incorrecto real |
| 25 | Falta instrucción de dispersar | Pagos con instrucción de dispersión |
| 26 | Resolución/convenio de colaboración | Causales regulatorias |
| 27 | Pago opcional no aceptado | Rechazo de un pago opcional |
| 28 | Tipo de pago CoDi sin notificación | Específico de CoDi |
| 30 | Clave de rastreo repetida | **No lo fuerces con este skill** — el simulador ya lo detecta solo (spec 014 §3) comparando contra lo que ya vio en el día operativo. Forzarlo aparte sería redundante; si quieres probar este caso, manda dos órdenes reales con la misma clave en vez de usar la palanca de rechazo forzado |

Motivos que el catálogo real define pero el simulador **no implementa** (4, 7-13, 18, 29) no
aparecen arriba y **no se pueden forzar** — `MotivoRechazo.porCodigo` solo reconoce los 20 códigos
de la tabla; pedir cualquier otro devuelve `400 motivo-invalido`. Si el usuario pide uno de estos,
dile que no está implementado en este simulador (no que "no existe" — si hace falta, es una
extensión real a `MotivoRechazo.java`, fuera del alcance de este skill).
