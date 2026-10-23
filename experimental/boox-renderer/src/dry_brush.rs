//! BOOX Art extensions, deliberately separate from recovered reference encodings.
//! Type 11: Pencil. Output records: x,y,major,minor,angle(degrees),opacity,tip index.
use super::*;

const MAX_DABS: usize = 100_000;

impl PenState {
    fn dry_dab(&mut self, ink: &mut InkData, point: Touch) {
        if self.dry_dabs >= MAX_DABS || ink.points.len() + 7 > MAX_OUTPUT_FLOAT_VALUES {
            ink.overflowed = true;
            return;
        }
        let pressure = 1.0 + self.config.pressure_sensitivity * (point.pressure - 1.0);
        let tilt = if self.config.tilt_enabled {
            let t = ((finite_or(point.tilt_x, 0.0) - 15.0) / 50.0).clamp(0.0, 1.0);
            t * t * (3.0 - 2.0 * t)
        } else { 0.0 };
        let major = self.config.width * (0.22 + 0.78 * pressure.sqrt()) * (1.0 + 1.5 * tilt);
        let minor = major / (1.0 + 1.5 * tilt);
        let opacity = 0.08 + 0.35 * pressure;
        let angle = if tilt > 0.0 { finite_or(point.tilt_y, 0.0) } else { 0.0 };
        ink.points.extend_from_slice(&[point.x, point.y, major, minor,
            angle, opacity, (self.dry_dabs % 4) as f32]);
        ink.point_sizes.push(7);
        self.dry_dabs += 1;
    }

    fn emit_dry(&mut self, input: &[Touch], phase: Phase) -> InkData {
        if phase == Phase::Down { self.reset(); }
        let mut ink = InkData::default();
        let spacing = (self.config.width * 0.15).max(0.5) as f64;
        for &end in input {
            if let Some(start) = self.last {
                let dx = (end.x - start.x) as f64;
                let dy = (end.y - start.y) as f64;
                let length = dx.hypot(dy);
                if !length.is_finite() { ink.overflowed = true; break; }
                let mut position = self.dry_remaining;
                while position <= length && length > 0.0 {
                    let t = (position / length) as f32;
                    let blend = |a: f32, b: f32| a + (b - a) * t;
                    self.dry_dab(&mut ink, Touch {
                        x: blend(start.x, end.x), y: blend(start.y, end.y),
                        pressure: blend(start.pressure, end.pressure),
                        tilt_x: blend(start.tilt_x, end.tilt_x),
                        // Shortest azimuth arc avoids spinning through a full turn.
                        tilt_y: start.tilt_y + ((end.tilt_y - start.tilt_y + 180.0).rem_euclid(360.0) - 180.0) * t,
                        ..end
                    });
                    if ink.overflowed { return ink; }
                    position += spacing;
                }
                self.dry_remaining = position - length;
                if phase == Phase::Up && length > 0.0 && self.dry_remaining < spacing - 1e-6 {
                    self.dry_dab(&mut ink, end);
                }
            } else {
                self.dry_dab(&mut ink, end);
                self.dry_remaining = spacing;
            }
            self.last = Some(end);
        }
        ink
    }

    pub(super) fn process_dry(&mut self, input: &[Touch], prediction: Option<Touch>, phase: Phase) -> (InkData, InkData) {
        let ink = self.emit_dry(input, phase);
        let predicted = prediction.map(|p| self.clone().emit_dry(&[p], Phase::Move)).unwrap_or_default();
        (ink, predicted)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pen() -> PenState { PenState::new(11, PenConfig { width: 8.0, pressure_sensitivity: 1.0,
        tilt_enabled: true, ..PenConfig::default() }) }
    fn point(x: f32) -> Touch { Touch { x, y: 20.0, pressure: 0.7, tilt_x: 65.0, ..Touch::default() } }
    #[test]
    fn distance_spacing_is_independent_of_input_batches_and_prediction() {
        let mut a = pen(); let mut b = pen();
        a.process(&[point(0.0)], None, Phase::Down);
        b.process(&[point(0.0)], None, Phase::Down);
        let input: Vec<_> = (1..100).map(|i| point(i as f32)).collect();
        let expected = a.process(&input, Some(point(120.0)), Phase::Move).0.points;
        let mut actual = Vec::new();
        for p in input { actual.extend(b.process(&[p], Some(point(200.0)), Phase::Move).0.points); }
        assert_eq!(expected, actual);
        assert!(a.history.is_empty() && b.history.is_empty());
        assert_eq!(a.dry_dabs, b.dry_dabs);
    }
    #[test]
    fn tilt_widens_and_rotates_shading_and_can_be_disabled() {
        let mut pen = pen();
        let up = pen.process(&[Touch { tilt_x: 0.0, ..point(0.0) }], None, Phase::Down).0;
        let flat = pen.process(&[Touch { tilt_y: 73.0, ..point(0.0) }], None, Phase::Down).0;
        assert!((flat.points[2] / up.points[2] - 2.5).abs() < 0.001);
        assert_eq!(flat.points[3], up.points[3]);
        assert_eq!(flat.points[4], 73.0);
        pen.config.tilt_enabled = false;
        assert_eq!(pen.process(&[point(0.0)], None, Phase::Down).0.points[2], up.points[2]);
    }
    #[test]
    fn pressure_dots_endpoints_and_absurd_jumps_are_bounded() {
        let mut pen = pen();
        let low = pen.process(&[Touch { pressure: 0.0, ..point(0.0) }], None, Phase::Down).0;
        let high = pen.process(&[Touch { pressure: 1.0, ..point(0.0) }], None, Phase::Down).0;
        assert!(low.points[2] > 0.0 && low.points[2] < high.points[2]);
        assert!(low.points[5] < high.points[5]);
        let tail = pen.process(&[point(0.2)], None, Phase::Up).0;
        assert_eq!(tail.points[0], 0.2);
        let huge = pen.process(&[point(1e20)], None, Phase::Move).0;
        assert!(huge.overflowed && huge.point_sizes.len() <= MAX_DABS);
    }
}
