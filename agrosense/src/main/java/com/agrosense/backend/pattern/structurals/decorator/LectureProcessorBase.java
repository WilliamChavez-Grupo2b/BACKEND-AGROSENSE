package com.agrosense.backend.pattern.estructural.decorator;

import com.agrosense.backend.domain.model.LectureSensor;
import org.springframework.stereotype.Component;

@Component
public class LectureProcessorBase implements LectureProcessor {
    @Override
    public LectureSensor procesar(LectureSensor lecture) {
        return lecture;
    }
}