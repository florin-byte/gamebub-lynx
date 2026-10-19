use embassy_time::Timer;
use esp_hal::gpio::Output;

#[embassy_executor::task]
pub async fn blink_task(mut led: Output<'static>) {
    loop {
        led.set_high();
        Timer::after_millis(150).await;
        led.set_low();
        Timer::after_millis(150).await;
        led.set_high();
        Timer::after_millis(150).await;
        led.set_low();
        Timer::after_millis(1000).await;
    }
}
