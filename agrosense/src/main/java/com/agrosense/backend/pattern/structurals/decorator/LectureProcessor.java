package com.agrosense.backend.pattern.estructural.decorator;

import com.agrosense.backend.domain.models.LectureSensor;

/**
 * Interfaz base para el patrón Decorator.
 * Define la operación que todos los procesadores deben implementar.
 */
public interface LectureProcessor {
    LectureSensor procesar(LectureSensor lecture);
}