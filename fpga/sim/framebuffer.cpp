#include "framebuffer.hpp"

Framebuffer::Framebuffer(int width, int height) {
    this->width = width;
    this->height = height;

    this->page0.resize(width * height * 4, 0xFF);
    this->page1.resize(width * height * 4, 0xFF);
}

void Framebuffer::update(bool hblank, bool vblank) {
    if (vblank && !prev_vblank) {
        activePage = !activePage;
        y = 0;
    } else if (!vblank && hblank && !prev_hblank) {
        y += 1;
        x = 0;
    }

    prev_hblank = hblank;
    prev_vblank = vblank;
}

void Framebuffer::clear() {
    std::fill(page0.begin(), page0.end(), 0xFF);
    std::fill(page1.begin(), page1.end(), 0xFF);
    x = 0;
    y = 0;
}

void Framebuffer::pushBGR(uint16_t pixel) {
    std::vector<uint8_t>& buffer = writeBuffer();

    int index = ((y * width) + x) * 4;

    uint8_t r = (pixel >> 0) & 0x1F;
    uint8_t g = (pixel >> 5) & 0x1F;
    uint8_t b = (pixel >> 10) & 0x1F;
    buffer[index + 0] = (b << 3) | (b >> 2);
    buffer[index + 1] = (g << 3) | (g >> 2);
    buffer[index + 2] = (r << 3) | (r >> 2);
    buffer[index + 3] = 0xFF;

    x += 1;
}

std::vector<uint8_t>& Framebuffer::writeBuffer() {
    return activePage ? page1 : page0;
}

std::vector<uint8_t>& Framebuffer::renderBuffer() {
    return activePage ? page0 : page1;
}
