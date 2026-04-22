import React from "react";
import { render } from "@testing-library/react";
import App from "./App";

jest.mock("axios", () => ({
    post: jest.fn(() => Promise.resolve({ data: [] })),
}));

test("renders app shell", () => {
    const { container } = render(<App />);
    expect(container.querySelector(".App")).toBeInTheDocument();
});
