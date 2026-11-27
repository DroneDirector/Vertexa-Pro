#pragma once
#include <cmath>

namespace vx {

struct M4 {
    double m[16];  // column-major
    static M4 identity() {
        M4 r{};
        for (int i = 0; i < 16; i++) r.m[i] = (i % 5 == 0) ? 1 : 0;
        return r;
    }
    M4 operator*(const M4& b) const {
        M4 r{};
        for (int c = 0; c < 4; c++)
            for (int rr = 0; rr < 4; rr++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += m[k * 4 + rr] * b.m[c * 4 + k];
                r.m[c * 4 + rr] = s;
            }
        return r;
    }
    void toFloat(float* out) const {
        for (int i = 0; i < 16; i++) out[i] = (float)m[i];
    }
};

struct V3 {
    double x = 0, y = 0, z = 0;
    V3() = default;
    V3(double a, double b, double c) : x(a), y(b), z(c) {}
    V3 operator+(const V3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    V3 operator-(const V3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    V3 operator*(double s) const { return {x * s, y * s, z * s}; }
    double dot(const V3& o) const { return x * o.x + y * o.y + z * o.z; }
    V3 cross(const V3& o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    double len() const { return std::sqrt(dot(*this)); }
    V3 norm() const { double l = len(); return l > 0 ? (*this) * (1.0 / l) : V3(0, 1, 0); }
};

inline M4 perspective(double fovy, double aspect, double n, double f) {
    double t = 1.0 / std::tan(fovy / 2);
    M4 r{};
    r.m[0] = t / aspect;
    r.m[5] = t;
    r.m[10] = (f + n) / (n - f);
    r.m[11] = -1;
    r.m[14] = 2 * f * n / (n - f);
    return r;
}

inline M4 lookAt(const V3& eye, const V3& at, const V3& up) {
    V3 f = (at - eye).norm();
    V3 s = f.cross(up).norm();
    V3 u = s.cross(f);
    M4 r = M4::identity();
    r.m[0] = s.x; r.m[4] = s.y; r.m[8] = s.z;
    r.m[1] = u.x; r.m[5] = u.y; r.m[9] = u.z;
    r.m[2] = -f.x; r.m[6] = -f.y; r.m[10] = -f.z;
    r.m[12] = -s.dot(eye);
    r.m[13] = -u.dot(eye);
    r.m[14] = f.dot(eye);
    return r;
}

inline M4 rotX(double a) {
    M4 r = M4::identity();
    double c = std::cos(a), s = std::sin(a);
    r.m[5] = c; r.m[6] = s; r.m[9] = -s; r.m[10] = c;
    return r;
}

inline V3 xform(const M4& m, const V3& p) {
    return {m.m[0] * p.x + m.m[4] * p.y + m.m[8] * p.z + m.m[12],
            m.m[1] * p.x + m.m[5] * p.y + m.m[9] * p.z + m.m[13],
            m.m[2] * p.x + m.m[6] * p.y + m.m[10] * p.z + m.m[14]};
}

}  // namespace vx
