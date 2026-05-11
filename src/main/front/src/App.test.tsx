import React from "react";
import { render } from "@testing-library/react";
import App from "./App";

jest.mock("axios", () => ({
    create: jest.fn(() => ({
        post: jest.fn(() => Promise.resolve({ data: [] })),
    })),
    post: jest.fn(() => Promise.resolve({ data: [] })),
}));

jest.mock("date-fns/locale", () => ({
    ko: {},
}));

test("renders app shell", () => {
    const { container } = render(<App />);
    expect(container.querySelector(".App")).toBeInTheDocument();
});
