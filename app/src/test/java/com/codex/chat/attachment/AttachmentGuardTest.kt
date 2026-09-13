package com.codex.chat.attachment

import com.codex.chat.core.attachment.AttachmentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentGuardTest {

    @Test fun acepta_un_fichero_pequeno() {
        assertTrue(AttachmentGuard.permitido(1024))
    }

    @Test fun acepta_exactamente_el_limite() {
        assertTrue("El limite exacto debe aceptarse",
            AttachmentGuard.permitido(AttachmentGuard.MAX_BYTES))
    }

    @Test fun rechaza_un_byte_por_encima_del_limite() {
        assertFalse(AttachmentGuard.permitido(AttachmentGuard.MAX_BYTES + 1))
    }

    @Test fun rechaza_un_video_de_60_MB() {
        assertFalse(AttachmentGuard.permitido(60L * 1024 * 1024))
    }

    @Test fun acepta_fichero_vacio() {
        assertTrue(AttachmentGuard.permitido(0))
    }

    @Test fun rechaza_tamano_negativo_o_desconocido() {
        assertFalse(AttachmentGuard.permitido(-1))
        assertNotNull(AttachmentGuard.motivoRechazo(-1))
    }

    @Test fun el_motivo_incluye_el_tamano_real_y_el_maximo() {
        val m = AttachmentGuard.motivoRechazo(60L * 1024 * 1024)!!
        assertTrue(m.contains("60"))
        assertTrue(m.contains("10"))
    }

    @Test fun sin_motivo_cuando_esta_permitido() {
        assertNull(AttachmentGuard.motivoRechazo(1024))
        assertNull(AttachmentGuard.motivoRechazo(AttachmentGuard.MAX_BYTES))
    }

    @Test fun el_calculo_de_base64_no_desborda_con_valores_grandes() {
        val grande = Long.MAX_VALUE / 2
        assertFalse(AttachmentGuard.permitido(grande))
    }
}
